package jp.ac.titech.c.se.stein;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;

import com.google.common.reflect.ClassPath;
import jp.ac.titech.c.se.stein.app.Identity;
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
            app.loadCommands(cmdline, path);
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

        log.debug("Rewriter: {}", rewriters.get(0).getClass().getName());

        openRepositories((source, target) -> {
            rewriters.get(0).initialize(source, target);
            log.info("Starting rewriting: {} -> {}", source.getDirectory(), target.getDirectory());
            final Context c = Context.init().with(Key.conf, conf);
            final Instant start = Instant.now();
            rewriters.get(0).rewrite(c);
            final Instant finish = Instant.now();
            log.info("Finished rewriting. Runtime: {} ms", Duration.between(start, finish).toMillis());
            if (!conf.isBare) {
                log.info("Checking out the HEAD...");
                new PorcelainAPI(target).checkout();
            }
            if (conf.isPackingEnabled) {
                log.info("Packing objects...");
                new PorcelainAPI(target).repack();
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
    protected void openRepositories(final BiConsumer<FileRepository, FileRepository> f) throws IOException {
        if (conf.output == null) {
            // source -> source
            try (final FileRepository repo = createRepository(conf.source, false)) {
                f.accept(repo, repo);
            }
            return;
        }

        // cleaning
        if (conf.output.isCleaningEnabled && conf.output.target.exists()) {
            log.info("Delete directory: {}", conf.output.target);
            FileUtils.deleteDirectory(conf.output.target);
        }

        if (conf.output.isDuplicating) {
            // target -> target (duplicate mode)
            log.info("Duplicate repository: {} -> {}", conf.source, conf.output.target);
            FileUtils.copyDirectory(conf.source, conf.output.target);
            try (final FileRepository repo = createRepository(conf.output.target, false)) {
                f.accept(repo, repo);
            }
            return;
        }

        // source -> target
        try (final FileRepository source = createRepository(conf.source, false)) {
            try (final FileRepository target = createRepository(conf.output.target, true)) {
                f.accept(source, target);
            }
        }
    }

    /**
     * Creates a repository object.
     */
    protected FileRepository createRepository(final File dir, final boolean createIfAbsent) throws IOException {
        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        if (conf.isBare) {
            builder.setGitDir(dir).setBare();
        } else {
            final File dotgit = new File(dir, Constants.DOT_GIT);
            builder.setWorkTree(dir).setGitDir(dotgit);
        }

        final FileRepository result = (FileRepository) builder.readEnvironment().build();
        if (!dir.exists() && createIfAbsent) {
            result.create(conf.isBare);
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
        final ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try {
            ClassPath.from(loader).getTopLevelClasses(pkg).stream()
                    .map(ClassPath.ClassInfo::load)
                    .filter(c -> c.isAnnotationPresent(Command.class))
                    .forEach(c -> {
                        log.info("Found command: {}", c.getName());
                        cmdline.addSubcommand(c);
                    });
        } catch (final IOException e) {
            log.error("Loading command", e);
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
