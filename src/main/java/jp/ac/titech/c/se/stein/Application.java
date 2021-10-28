package jp.ac.titech.c.se.stein;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;

import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import ch.qos.logback.classic.Level;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.Context.Key;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(version = "git-stein", sortOptions = false)
public class Application implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    public static class Config {
        public static final int MIDDLE = 5;
        public static final int LOW = 8;
        public static final int LAST = 10;

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

        @Option(names = "--mapping", paramLabel = "<file>", description = "store the commit mapping", order = LOW)
        File commitMappingFile;

        @Option(names = "--log", paramLabel = "<level>", description = "log level (default: ${DEFAULT-VALUE})", order = LOW, converter = LevelConverter.class)
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
    }

    @Mixin
    Config conf = new Config();

    @Mixin
    final RepositoryRewriter rewriter;

    public static void setLoggerLevel(final String name, final Level level) {
        final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
        logger.setLevel(level);
        log.debug("Set log level of {} to {}", name, level);
    }

    public Application(final RepositoryRewriter rewriter) {
        this.rewriter = rewriter;
    }

    @Override
    public Integer call() throws Exception {
        setLoggerLevel(Logger.ROOT_LOGGER_NAME, conf.logLevel);
        if (conf.logLevel == Level.DEBUG) {
            // suppress jgit's log
            setLoggerLevel("org.eclipse.jgit", Level.INFO);
        }

        log.debug("Rewriter: {}", rewriter.getClass().getName());

        openRepositories((source, target) -> {
            rewriter.initialize(source, target);
            log.info("Starting rewriting: {} -> {}", source.getDirectory(), target.getDirectory());
            final Context c = Context.init().with(Key.conf, conf);
            final Instant start = Instant.now();
            rewriter.rewrite(c);
            final Instant finish = Instant.now();
            log.info("Finished rewriting. Runtime: {} ms", Duration.between(start, finish).toMillis());
        });

        if (conf.commitMappingFile != null) {
            log.debug("Save commit mapping to {}", conf.commitMappingFile);
            final byte[] content = new Gson().toJson(rewriter.exportCommitMapping()).getBytes();
            FileUtils.writeByteArrayToFile(conf.commitMappingFile, content);
        }

        return 0;
    }

    /**
     * Opens the source and target repositories and run the given block.
     */
    protected void openRepositories(final BiConsumer<Repository, Repository> f) throws IOException {
        if (conf.output == null) {
            // source -> source
            try (final Repository repo = createRepository(conf.source, false)) {
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
            try (final Repository repo = createRepository(conf.output.target, false)) {
                f.accept(repo, repo);
            }
            return;
        }

        // source -> target
        try (final Repository source = createRepository(conf.source, false)) {
            try (final Repository target = createRepository(conf.output.target, true)) {
                f.accept(source, target);
            }
        }
    }

    /**
     * Creates a repository object.
     */
    protected Repository createRepository(final File dir, final boolean createIfAbsent) throws IOException {
        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        if (conf.isBare) {
            builder.setGitDir(dir).setBare();
        } else {
            final File dotgit = new File(dir, Constants.DOT_GIT);
            builder.setWorkTree(dir).setGitDir(dotgit);
        }

        final Repository result = builder.readEnvironment().build();
        if (!dir.exists() && createIfAbsent) {
            result.create(conf.isBare);
        }
        return result;
    }

    public static class LevelConverter implements ITypeConverter<Level> {
        @Override
        public Level convert(final String value) {
            return Level.valueOf(value);
        }
    }

    public static void execute(final RepositoryRewriter rewriter, final String[] args) {
        final Application app = new Application(rewriter);
        final CommandLine cmdline = new CommandLine(app);
        cmdline.setExpandAtFiles(false);

        final int status = cmdline.execute(args);
        System.exit(status);
    }
}
