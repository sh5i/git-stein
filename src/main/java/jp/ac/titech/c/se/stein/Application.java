package jp.ac.titech.c.se.stein;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

import jp.ac.titech.c.se.stein.app.blob.FilterBlob;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.app.Identity;
import jp.ac.titech.c.se.stein.rewriter.RewriterCommand;
import jp.ac.titech.c.se.stein.util.SettableHelpCommand;
import jp.ac.titech.c.se.stein.util.Loader;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.Context.Key;
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
    public interface TetraConsumer<T, U, V, W> {
        void accept(T t, U u, V v, W w);
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
                final int nprocs = Runtime.getRuntime().availableProcessors();
                this.nthreads = nprocs > 1 ? nprocs - 1 : 1;
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

        @Option(names = "--cache", split = ",", paramLabel = "<l>", description = "cache level (${COMPLETION-CANDIDATES}. default: none)", order = MIDDLE)
        public EnumSet<RepositoryRewriter.CacheLevel> cacheLevel = EnumSet.noneOf(RepositoryRewriter.CacheLevel.class);

        @Option(names = "--extra-attributes", description = "rewrite encoding and signature in commits", order = MIDDLE)
        public boolean isRewritingExtraAttributes = false;

        @SuppressWarnings("unused")
        @Option(names = "--stream-size-limit", paramLabel = "<num>{,K,M,G}", description = "increase stream size limit", order = MIDDLE,
                converter = FilterBlob.SizeConverter.class)
        void setSizeLimit(final long limit) {
            // default: 50MB is too small
            final int intLimit = (int) Math.min(limit, Integer.MAX_VALUE);
            log.info("Set stream size limit: {}", intLimit);
            final WindowCacheConfig config = new WindowCacheConfig();
            config.setStreamFileThreshold(intLimit);
            config.install();
        }

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
        openRepositories((source, target, rewriter, index) -> {
            log.info("Starting rewriting [{}]: {} -> {}", rewriter, source.getDirectory(), target.getDirectory());
            rewriter.setConfig(conf);
            rewriter.initialize(source, target);
            final Context c = Context.init().with(Key.conf, conf);
            final Instant start = Instant.now();
            rewriter.rewrite(c);
            final Instant finish = Instant.now();
            log.info("Completed rewriting in {} ms", Duration.between(start, finish).toMillis());
            if (conf.isPackingEnabled) {
                log.info("Packing objects in {}...", target.getDirectory());
                new PorcelainAPI(target).repack();
            }
            if (!conf.isBare && index == rewriters.size() - 1) {
                log.info("Checking out HEAD of {}...", target.getDirectory());
                new PorcelainAPI(target).checkout();
            }
        });

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
        setLoggerLevel(Logger.ROOT_LOGGER_NAME, conf.logLevel);

        // help
        if (parseResult.subcommands().size() >= 2) {
            final Object cmd = parseResult.subcommands().get(0).commandSpec().userObject();
            if (cmd instanceof SettableHelpCommand) {
                ((SettableHelpCommand) cmd).setCommand(parseResult.subcommands().get(1).commandSpec().name());
            }
        }
        if (CommandLine.printHelpIfRequested(parseResult)) {
            return 0;
        }

        if (parseResult.subcommands().isEmpty()) {
            throw new ParameterException(parseResult.commandSpec().commandLine(), "No subcommands");
        }
        final List<RewriterCommand> commands = parseResult.subcommands().stream()
                .map(pr -> (RewriterCommand) pr.commandSpec().userObject())
                .collect(Collectors.toList());
        if (conf.useComposite) {
            log.debug("Optimizing rewriters...");
            optimizeRewriters(commands);
        }
        this.rewriters.addAll(prepareRewriters(commands));

        try {
            return this.call();
        } catch (final Exception e) {
            throw new ExecutionException(parseResult.commandSpec().commandLine(), "Execution failed.", e);
        }
    }

    public List<RepositoryRewriter> prepareRewriters(final List<RewriterCommand> commands) {
        final List<RepositoryRewriter> result = new ArrayList<>();
        for (final RewriterCommand cmd : commands) {
            if (cmd instanceof RepositoryRewriter) {
                result.add((RepositoryRewriter) cmd);
            } else if (cmd instanceof RepositoryRewriter.Factory) {
                result.add(((RepositoryRewriter.Factory) cmd).create());
            } else {
                log.error("Unknown command: {}", cmd.getClass());
            }
        }
        return result;
    }

    public void optimizeRewriters(final List<RewriterCommand> commands) {
        for (int i = 0; i < commands.size(); i++) {
            if (commands.get(i) instanceof BlobTranslator) {
                // extract a sequence of blob translators
                final List<BlobTranslator> translators = new ArrayList<>();
                do {
                    translators.add((BlobTranslator) commands.remove(i));
                } while (i < commands.size() && commands.get(i) instanceof BlobTranslator);

                // insert new rewriter
                if (translators.size() >= 2) {
                    log.info("Compose {} blob translators: {}", translators.size(), translators);
                    commands.add(new BlobTranslator.Composite(translators));
                } else {
                    commands.add(new BlobTranslator.Single(translators.get(0)));
                }
            }
        }
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
        final Application app = new Application();
        final CommandLine cmdline = new CommandLine(app);
        loadCommands(cmdline, BUILTIN_COMMAND_PACKAGE);
        cmdline.setExpandAtFiles(false);
        cmdline.setExecutionStrategy(app);
        final int status = cmdline.execute(args);
        System.exit(status);
    }
}
