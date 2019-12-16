package jp.ac.titech.c.se.stein;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import ch.qos.logback.classic.Level;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import jp.ac.titech.c.se.stein.core.Try;
import picocli.CommandLine;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(version = "git-stein", sortOptions = false)
public class Application implements Callable<Integer> {
    public static final int LOW = 10000;

    private static final Logger log = LoggerFactory.getLogger(Application.class);

    static class Config {
        @Parameters(index = "0", paramLabel = "<repo>", description = "source repo")
        File source;

        @ArgGroup(exclusive = false, multiplicity = "0..1")
        OutputOptions output;

        static class OutputOptions {
            @Option(names = { "-o", "--output" }, required = true, paramLabel = "<path>", description = "destination repo")
            File destination;

            @Option(names = { "-d", "--duplicate" }, required = false, description = "duplicate source repo and overwrite it")
            boolean isDuplicating;

            @Option(names = "--clean", required = false, description = "delete destination repo beforehand if exists")
            boolean isCleaningEnabled;
        }

        @Option(names = "--bare", description = "treat that repos are bare")
        boolean isBare;

        @Option(names = "--mapping", paramLabel = "<file>", description = "store the commit mapping", order = LOW)
        File commitMappingFile;

        @Option(names = "--log", paramLabel = "<level>", description = "log level (default: ${DEFAULT-VALUE})", order = LOW)
        Level logLevel = Level.INFO;

        @Option(names = { "-q", "--quiet" }, description = "quiet mode (same as --log=ERROR)", order = LOW)
        void setQuiet(final boolean isQuiet) {
            if (isQuiet) {
                logLevel = Level.ERROR;
            }
        }

        @Option(names = { "-v", "--verbose" }, description = "verbose mode (same as --log=DEBUG)", order = LOW)
        void setVerbose(final boolean isVerbose) {
            if (isVerbose) {
                logLevel = Level.DEBUG;
            }
        }

        @Option(names = "--help", description = "show this help message and exit", usageHelp = true, order = LOW)
        boolean helpRequested;

        @Option(names = "--version", description = "print version information and exit", versionHelp = true, order = LOW)
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

        openRepositories((src, dst) -> {
            log.debug("Source repository: {}", src.getDirectory());
            log.debug("Destination repository: {}", dst.getDirectory());
            rewriter.initialize(src, dst);

            final Instant start = Instant.now();
            log.info("Starting rewriting...");

            rewriter.rewrite();

            final Instant finish = Instant.now();
            log.info("Finished rewriting. Runtime: {} ms", Duration.between(start, finish).toMillis());
        });

        if (conf.commitMappingFile != null) {
            Try.io(() -> exportObject(rewriter.exportCommitMapping(), conf.commitMappingFile));
        }

        return 0;
    }

    /**
     * Returns the source and destination repository objects.
     */
    protected void openRepositories(final BiConsumer<Repository, Repository> f) throws IOException {
        if (conf.output == null) {
            // source -> source
            final Repository repo = openRepository(conf.source, false);
            tryOpenRepositories(repo, repo, f);
        } else {
            // cleaning
            if (conf.output.isCleaningEnabled && conf.output.destination.exists()) {
                deleteDirectory(conf.output.destination.toPath());
            }

            if (conf.output.isDuplicating) {
                // destination -> destination (duplicate mode)
                copyDirectory(conf.source.toPath(), conf.output.destination.toPath());
                final Repository repo = openRepository(conf.output.destination, false);
                tryOpenRepositories(repo, repo, f);
            } else {
                // source -> destination
                final Repository src = openRepository(conf.source, false);
                final Repository dst = openRepository(conf.output.destination, true);
                tryOpenRepositories(src, dst, f);
            }
        }
    }

    protected void tryOpenRepositories(final Repository src, final Repository dst, final BiConsumer<Repository, Repository> f) throws IOException {
        try (final Repository readRepo = src) {
            if (src != dst) {
                try (final Repository writeRepo = dst) {
                    f.accept(src, dst);
                }
            } else {
                f.accept(src, src);
            }
        }
    }

    protected Repository openRepository(final File dir, final boolean create) throws IOException {
        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        if (conf.isBare) {
            builder.setGitDir(dir).setBare();
        } else {
            final File dotgit = new File(dir, Constants.DOT_GIT);
            builder.setWorkTree(dir).setGitDir(dotgit);
        }

        final Repository result = builder.readEnvironment().build();
        if (!dir.exists() && create) {
            result.create(conf.isBare);
        }
        return result;
    }

    /**
     * Returns the input repository object.
     */

    /**
     * Dump an object to a file as JSON format.
     */
    protected void exportObject(final Object object, final File file) throws IOException {
        final Gson gson = new Gson();
        Files.write(file.toPath(), gson.toJson(object).getBytes());
    }

    /**
     * Copy a directory recursively.
     */
    protected void copyDirectory(final Path source, final Path target) throws IOException {
        log.debug("Duplicate repository: {} to {}", source, target);
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)));
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Delete a directory recursively.
     */
    protected void deleteDirectory(final Path target) throws IOException {
        log.debug("Delete directory: {}", target);
        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path dir, IOException exc) throws IOException {
                if (exc == null) {
                    Files.delete(dir);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void execute(final RepositoryRewriter rewriter, String[] args) {
        final Application app = new Application(rewriter);
        final CommandLine cmdline = new CommandLine(app);
        cmdline.setExpandAtFiles(false);
        cmdline.registerConverter(Level.class, s -> Level.valueOf(s));

        final int status = cmdline.execute(args);
        System.exit(status);
    }
}
