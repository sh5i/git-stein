package jp.ac.titech.c.se.stein;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import jp.ac.titech.c.se.stein.app.Identity;
import jp.ac.titech.c.se.stein.rewriter.RewriterCommand;
import jp.ac.titech.c.se.stein.util.SettableHelpCommand;
import jp.ac.titech.c.se.stein.util.Loader;
import jp.ac.titech.c.se.stein.util.SizeConverter;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.Context.Key;
import jp.ac.titech.c.se.stein.core.RefNamespace;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import org.slf4j.event.Level;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(name = "git-stein", sortOptions = false, subcommandsRepeatable = true,
         subcommands = {SettableHelpCommand.class}, usageHelpAutoWidth = true)
public class Application implements Callable<Integer>, CommandLine.IExecutionStrategy {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static final String BUILTIN_COMMAND_PACKAGE = Identity.class.getPackageName();

    @FunctionalInterface
    public interface StageConsumer {
        void accept(FileRepository sourceRepo, RefNamespace sourceNamespace,
                    FileRepository targetRepo, RefNamespace targetNamespace,
                    RepositoryRewriter rewriter, int index);
    }

    public static class Config {
        public static final int MIDDLE = 5;
        public static final int LOW = 8;
        public static final int LAST = 10;

        @Spec(Spec.Target.MIXEE)
        public Model.CommandSpec commandSpec;

        @Parameters(index = "0", paramLabel = "<repo>", description = "source repo")
        public File source;

        @ArgGroup(exclusive = false)
        public OutputOptions output;

        public static class OutputOptions {
            @Option(names = {"-o", "--output"}, paramLabel = "<path>", description = "destination repo", required = true)
            public File target;

            @Option(names = {"-d", "--duplicate"}, description = "duplicate source repo and overwrite it")
            public boolean isDuplicating;

            @Option(names = "--clean", description = "delete destination repo beforehand if exists")
            public boolean isCleaningEnabled;
        }

        @Option(names = "--bare", description = "treat that repos are bare")
        public boolean isBare = false;

        @SuppressWarnings("unused")
        @Option(names = {"-j", "--jobs"}, paramLabel = "<nthreads>", description = "number of threads to rewrite trees in parallel", order = MIDDLE,
                fallbackValue = "0")
        void setNumberOfThreads(final int nthreads) {
            this.nthreads = nthreads;
            if (nthreads == 0) {
                this.nthreads = Runtime.getRuntime().availableProcessors();
            }
        }
        public int nthreads = 1;

        @Option(names = {"-n", "--dry-run"}, description = "do not actually touch destination repo", order = MIDDLE)
        public boolean isDryRunning = false;

        @Option(names = "--no-notes", negatable = true, description = "note original commit id to destination repo", order = MIDDLE)
        public boolean isAddingNotes = true;

        @Option(names = "--pack", negatable = true, description = "pack objects (default: ${DEFAULT-VALUE})", order = MIDDLE)
        public boolean isPackingEnabled = false;

        @Option(names = "--no-composite", negatable = true, description = "compose multiple blob translators (default: ${DEFAULT-VALUE})", order = MIDDLE)
        public boolean useComposite = true;

        public enum AlternatesMode { relative, absolute }

        @Option(names = "--alternates", description = "share source objects via alternates (${COMPLETION-CANDIDATES}; default: relative)",
                fallbackValue = "relative", order = MIDDLE, arity = "0..1")
        public AlternatesMode alternatesMode;

        @Option(names = "--cache", description = "enable persistent entry caching", order = MIDDLE)
        public boolean isCachingEnabled = false;

        @Option(names = "--mapping-mem", paramLabel = "<num>{,K,M,G}", description = "max memory for entry mapping (default: 25%% of max heap)", order = MIDDLE,
                converter = SizeConverter.class)
        public long entryMappingMemory = -1;

        @Option(names = "--extra-attributes", description = "rewrite encoding and signature in commits", order = MIDDLE)
        public boolean isRewritingExtraAttributes = false;

        @SuppressWarnings("unused")
        @Option(names = "--cmdpath", split = ":", paramLabel = "<p>", description = "add packages for search for commands", order = LOW)
        void setCommandPath(final String[] paths) {
            final Application app = (Application) commandSpec.root().userObject();
            final CommandLine cmdline = commandSpec.root().commandLine();
            for (final String path : paths) {
                loadCommands(cmdline, path);
            }
        }

        @Option(names = "--log", paramLabel = "<level>", description = "log level (default: ${DEFAULT-VALUE})", order = LOW)
        public Level logLevel = Level.INFO;

        @SuppressWarnings("unused")
        @Option(names = {"-q", "--quiet"}, description = "quiet mode (same as --log=ERROR)", order = LOW)
        void setQuiet(final boolean isQuiet) {
            if (isQuiet) {
                logLevel = Level.ERROR;
            }
        }

        @SuppressWarnings("unused")
        @Option(names = {"-v", "--verbose"}, description = "verbose mode (same as --log=DEBUG)", order = LOW)
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
        openRepositories((sourceRepo, sourceNamespace, targetRepo, targetNamespace, rewriter, index) -> {
            log.info("Starting rewriting [{}]: {} -> {}", rewriter, sourceRepo.getDirectory(), targetRepo.getDirectory());
            rewriter.setConfig(conf);
            rewriter.initialize(sourceRepo, sourceNamespace, targetRepo, targetNamespace);
            rewriter.useDefaultScope();
            // Alternates only make sense at the external boundary (first stage, distinct repos);
            // internal stages share the target's object store directly.
            if (conf.alternatesMode != null && index == 0 && sourceRepo != targetRepo) {
                new RepositoryAccess(targetRepo).setupAlternates(sourceRepo, conf.alternatesMode == Config.AlternatesMode.relative);
            }
            final Context c = Context.init().with(Key.conf, conf);
            final Instant start = Instant.now();
            rewriter.rewrite(c);
            final Instant finish = Instant.now();
            log.info("Completed rewriting in {} ms", Duration.between(start, finish).toMillis());
            final boolean isLast = index == rewriters.size() - 1;
            // Every stage shares one object store, so pack and check out once, after the final stage.
            if (conf.isPackingEnabled && isLast) {
                log.info("Packing objects in {}...", targetRepo.getDirectory());
                new PorcelainAPI(targetRepo).repack();
            }
            if (!conf.isBare && isLast) {
                log.info("Checking out HEAD of {}...", targetRepo.getDirectory());
                new PorcelainAPI(targetRepo).checkout();
            }
        });

        return 0;
    }

    /**
     * Opens the source and target repositories and runs the given block once per rewriter.
     *
     * <p>A pipeline of N rewriters runs entirely within the single target repository, with each
     * intermediate version held under its own ref namespace ({@code refs/namespaces/git-stein.k/}).
     * The first stage reads the external source at the root namespace; the last stage writes the
     * final result back to the root namespace. Staging namespaces are left in place.</p>
     */
    protected void openRepositories(final StageConsumer f) throws IOException {
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
        final boolean isInPlace = target.equals(conf.source);

        try (final FileRepository targetRepo = createRepository(target, conf.isBare, true)) {
            if (isInPlace) {
                runPipeline(f, targetRepo, targetRepo);
            } else {
                try (final FileRepository sourceRepo = createRepository(conf.source, conf.isBare, false)) {
                    runPipeline(f, sourceRepo, targetRepo);
                }
            }
        }
    }

    /**
     * Runs each rewriter as a stage, threading intermediate versions through ref namespaces of the
     * single target repository. Only the first stage reads from {@code externalSource}.
     */
    private void runPipeline(final StageConsumer f, final FileRepository externalSource, final FileRepository targetRepo) {
        final int n = rewriters.size();
        for (int i = 0; i < n; i++) {
            final FileRepository sourceRepo = i == 0 ? externalSource : targetRepo;
            final RefNamespace sourceNamespace = i == 0 ? RefNamespace.ROOT : versionNamespace(i);
            final RefNamespace targetNamespace = i == n - 1 ? RefNamespace.ROOT : versionNamespace(i + 1);
            f.accept(sourceRepo, sourceNamespace, targetRepo, targetNamespace, rewriters.get(i), i);
        }
    }

    /**
     * The ref namespace holding the k-th intermediate version of a pipeline.
     */
    protected RefNamespace versionNamespace(final int k) {
        return new RefNamespace("refs/namespaces/git-stein." + k + "/");
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
        setLoggerLevel(Logger.ROOT_LOGGER_NAME, conf.logLevel);

        // help
        if (parseResult.subcommands().size() >= 2) {
            final Object cmd = parseResult.subcommands().get(0).commandSpec().userObject();
            if (cmd instanceof SettableHelpCommand shc) {
                shc.setCommand(parseResult.subcommands().get(1).commandSpec().name());
            }
        }
        if (CommandLine.printHelpIfRequested(parseResult)) {
            return 0;
        }

        if (parseResult.subcommands().isEmpty()) {
            throw new ParameterException(parseResult.commandSpec().commandLine(), "No subcommands");
        }
        List<RewriterCommand> commands = parseResult.subcommands().stream()
                .map(pr -> (RewriterCommand) pr.commandSpec().userObject())
                .collect(Collectors.toList());
        if (conf.useComposite) {
            log.debug("Optimizing rewriters...");
            commands = RewriterCommand.optimize(commands);
        }
        this.rewriters.addAll(prepareRewriters(commands));

        try {
            return this.call();
        } catch (final Exception e) {
            throw new ExecutionException(parseResult.commandSpec().commandLine(), "Execution failed.", e);
        }
    }

    public List<RepositoryRewriter> prepareRewriters(final List<RewriterCommand> commands) {
        return commands.stream().map(RewriterCommand::toRewriter).collect(Collectors.toList());
    }

    /**
     * Add all the command classes found in the given package as subcommands to the given commandline.
     */
    public static void loadCommands(final CommandLine cmdline, final String pkg) {
        for (final Class<? extends RewriterCommand> c : Loader.enumerateCommands(pkg, true)) {
            log.debug("Register command: {}", c.getName());
            cmdline.addSubcommand(c);
        }
    }

    public static void main(final String[] args) {
        RepositoryAccess.setStreamFileThreshold(Integer.MAX_VALUE);

        final Application app = new Application();
        final CommandLine cmdline = new CommandLine(app);
        loadCommands(cmdline, BUILTIN_COMMAND_PACKAGE);
        cmdline.setExpandAtFiles(false);
        cmdline.setExecutionStrategy(app);
        final int status = cmdline.execute(args);
        System.exit(status);
    }
}
