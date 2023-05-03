package jp.ac.titech.c.se.stein;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import jp.ac.titech.c.se.stein.app.Identity;
import jp.ac.titech.c.se.stein.core.RewriterCommand;
import jp.ac.titech.c.se.stein.util.Loader;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.Context.Key;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import org.slf4j.event.Level;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(version = "git-stein", sortOptions = false, subcommandsRepeatable = true)
public class Application implements Callable<Integer>, CommandLine.IExecutionStrategy {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static final String BUILTIN_COMMAND_PACKAGE = Identity.class.getPackageName();

    @FunctionalInterface
    public interface TetraConsumer<T, U, V, W> {
        void accept(T t, U u, V v, W w);
    }

    public static class Config {
        public static final int MIDDLE = 5;
        public static final int LOW = 8;
        public static final int LAST = 10;

        @Spec(Spec.Target.MIXEE)
        Model.CommandSpec commandSpec;

        @Parameters(index = "0", paramLabel = "<repo>", description = "source repo")
        File source;

        @ArgGroup(exclusive = false)
        OutputOptions output;

        static class OutputOptions {
            @Option(names = { "-o", "--output" }, paramLabel = "<path>", description = "destination repo", required = true)
            File target;

            @Option(names = { "-d", "--duplicate" }, description = "duplicate source repo and overwrite it")
            boolean isDuplicating;

            @Option(names = "--clean", description = "delete destination repo beforehand if exists")
            boolean isCleaningEnabled;
        }

        @Option(names = "--bare", description = "treat that repos are bare")
        boolean isBare;

        @Option(names = "--pack", description = "pack objects")
        boolean isPackingEnabled;

        @Option(names = "--mapping", paramLabel = "<file>", description = "store the commit mapping", order = LOW)
        File commitMappingFile;

        @Option(names = "--log", paramLabel = "<level>", description = "log level (default: ${DEFAULT-VALUE})", order = LOW)
        Level logLevel = Level.INFO;

        @SuppressWarnings("unused")
        @Option(names = { "-q", "--quiet" }, description = "quiet mode (same as --log=ERROR)", order = LOW)
        void setQuiet(final boolean isQuiet) {
            if (isQuiet) {
                logLevel = Level.ERROR;
            }
        }

        @SuppressWarnings("unused")
        @Option(names = { "-v", "--verbose" }, description = "verbose mode (same as --log=DEBUG)", order = LOW)
        void setVerbose(final boolean isVerbose) {
            if (isVerbose) {
                logLevel = Level.DEBUG;
            }
        }

        @SuppressWarnings("unused")
        @Option(names = "--help", description = "show this help message and exit", order = LAST, usageHelp = true)
        boolean helpRequested;

        @SuppressWarnings("unused")
        @Option(names = "--version", description = "print version information and exit", order = LAST, versionHelp = true)
        boolean versionInfoRequested;

        @SuppressWarnings("unused")
        @Option(names = "--cmdpath", arity = "0..*",
                paramLabel = "<pkg>", description = "add path package for command classes")
        void setCommandPath(final String path) {
            final Application app = (Application) commandSpec.root().userObject();
            final CommandLine cmdline = commandSpec.root().commandLine();
            loadCommands(cmdline, path);
        }
    }

    @Mixin
    Config conf = new Config();

    final List<RepositoryRewriter> rewriters = new ArrayList<>();

    public static void setLoggerLevel(final String name, final Level level) {
        final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
        logger.setLevel(ch.qos.logback.classic.Level.convertAnSLF4JLevel(level));
        log.debug("Set log level of {} to {}", name, level);
    }

    @Override
    public Integer call() throws Exception {
        setLoggerLevel(Logger.ROOT_LOGGER_NAME, conf.logLevel);
        if (conf.logLevel == Level.DEBUG || conf.logLevel == Level.TRACE) {
            // suppress jgit's log
            setLoggerLevel("org.eclipse.jgit", Level.INFO);
        }

        openRepositories((source, target, rewriter, index) -> {
            log.debug("Rewriter: {}", rewriter.getClass().getName());
            rewriter.initialize(source, target);
            log.info("Starting rewriting: {} -> {}", source.getDirectory(), target.getDirectory());
            final Context c = Context.init().with(Key.conf, conf);
            final Instant start = Instant.now();
            rewriter.rewrite(c);
            final Instant finish = Instant.now();
            log.info("Finished rewriting. Runtime: {} ms", Duration.between(start, finish).toMillis());
            if (conf.isPackingEnabled) {
                log.info("Packing objects...");
                new PorcelainAPI(target).repack();
            }
            if (!conf.isBare && index == rewriters.size() - 1) {
                log.info("Checking out the HEAD...");
                new PorcelainAPI(target).checkout();
            }
        });

        if (conf.commitMappingFile != null) {
            log.debug("Save commit mapping to {}", conf.commitMappingFile);
            final byte[] content = new Gson().toJson(rewriters.get(0).exportCommitMapping()).getBytes();
            FileUtils.writeByteArrayToFile(conf.commitMappingFile, content);
        }

        return 0;
    }

    /**
     * Opens the source and target repositories and run the given block.
     */
    protected void openRepositories(final TetraConsumer<FileRepository, FileRepository, RepositoryRewriter, Integer> f) throws IOException {
        // cleaning
        if (conf.output != null && conf.output.isCleaningEnabled && conf.output.target.exists()) {
            log.info("Delete directory: {}", conf.output.target);
            FileUtils.deleteDirectory(conf.output.target);
        }

        // target -> target (duplicate mode)
        if (conf.output != null && conf.output.isDuplicating) {
            log.info("Duplicate repository: {} -> {}", conf.source, conf.output.target);
            FileUtils.copyDirectory(conf.source, conf.output.target);
        }

        final File target = conf.output != null ? conf.output.target : conf.source;

        if (rewriters.size() > 1) {
            try (final FileRepository repo = createRepository(target, conf.isBare, true)) {
                // create unless exist
                log.debug("Target repo: {}", repo.getDirectory());
            }
        }

        for (int i = 0; i < rewriters.size(); i++) {
            final File src = i == 0 ? conf.source : createIntermediateRepositoryName(target, i);
            final File dst = i == rewriters.size() - 1 ? target : createIntermediateRepositoryName(target, i + 1);
            final boolean isSrcBare = i != 0 || conf.isBare;
            final boolean isDstBare = i != rewriters.size() - 1 || conf.isBare;
            if (src.equals(dst)) {
                try (final FileRepository repo = createRepository(src, isSrcBare, false)) {
                    f.accept(repo, repo, rewriters.get(i), i);
                }
            } else {
                try (final FileRepository sourceRepo = createRepository(src, isSrcBare, false)) {
                    try (final FileRepository targetRepo = createRepository(dst, isDstBare, true)) {
                        f.accept(sourceRepo, targetRepo, rewriters.get(i), i);
                    }
                }
            }
        }
    }

    /**
     * Generate intermediate repository name.
     */
    protected File createIntermediateRepositoryName(final File target, final int n) {
        final File dotgit = conf.isBare ? target : new File(target, Constants.DOT_GIT);
        return new File(dotgit, ".git-stein." + n);
    }

    /**
     * Creates a repository object.
     */
    protected FileRepository createRepository(final File dir, final boolean isBare, final boolean createIfAbsent) throws IOException {
        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        if (isBare) {
            builder.setGitDir(dir).setBare();
        } else {
            final File dotgit = new File(dir, Constants.DOT_GIT);
            builder.setWorkTree(dir).setGitDir(dotgit);
        }

        final FileRepository result = (FileRepository) builder.readEnvironment().build();
        if (!dir.exists() && createIfAbsent) {
            result.create(isBare);
        }
        return result;
    }

    @Override
    public int execute(final ParseResult parseResult) throws ExecutionException, ParameterException {
        if (CommandLine.printHelpIfRequested(parseResult)) {
            return 0;
        }
        if (parseResult.subcommands().isEmpty()) {
            throw new ParameterException(parseResult.commandSpec().commandLine(), "No subcommands");
        }
        for (final ParseResult pr : parseResult.subcommands()) {
            final Object obj = pr.commandSpec().userObject();
            if (obj instanceof RepositoryRewriter) {
                this.rewriters.add((RepositoryRewriter) obj);
            } else if (obj instanceof RepositoryRewriter.Factory) {
                final RepositoryRewriter rewriter = ((RepositoryRewriter.Factory) obj).create();
                this.rewriters.add(rewriter);
            }
        }
        try {
            return this.call();
        } catch (final Exception e) {
            throw new ExecutionException(parseResult.commandSpec().commandLine(), "Execution failed.", e);
        }
    }

    /**
     * Add all the command classes found in the given package as subcommands to the given commandline.
     */
    public static void loadCommands(final CommandLine cmdline, final String pkg) {
        for (final Class<? extends RewriterCommand> c : Loader.enumerateCommands(pkg, true)) {
            log.info("Found command: {}", c.getName());
            cmdline.addSubcommand(c);
        }
    }

    public static void main(final String[] args) {
        final Application app = new Application();
        final CommandLine cmdline = new CommandLine(app);
        loadCommands(cmdline, BUILTIN_COMMAND_PACKAGE);
        cmdline.setExpandAtFiles(false);
        cmdline.setExecutionStrategy(app);
        final int status = cmdline.execute(args);
        System.exit(status);
    }
}
