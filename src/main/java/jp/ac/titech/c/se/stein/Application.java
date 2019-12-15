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
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

public class Application implements Callable<Integer> {
    private static final Logger log = LoggerFactory.getLogger(Application.class);

    class Config {
        @Parameters(index = "0", paramLabel = "<repo>", description = "source repo")
        File input;

        @Option(names = { "-o", "--output" }, paramLabel = "<path>", description = "destination repo")
        File output;

        @Option(names = { "-d", "--output-dup" }, paramLabel = "<path>", description = "output path (duplicate-and-overwrite)")
        File duplicatedOutput;

        @Option(names = "--bare", description = "treat that repos are bare")
        boolean isBare;

        @Option(names = "--clean", description = "delete destination repo beforehand if exists")
        boolean isCleanEnabled;

        @Option(names = "--commit-mapping", paramLabel = "<file>", description = "store the commit mapping")
        File commitMappingFile;

        Level logLevel = Level.INFO;

        @Option(names = "--log", description = "log level (default: INFO)")
        void setLevel(final String level) {
            logLevel = Level.valueOf(level);
        }

        @Option(names = { "-q", "--quiet" }, description = "quiet mode (same as --log=ERROR)")
        void setQuiet(final boolean isQuiet) {
            if (isQuiet) {
                logLevel = Level.ERROR;
            }
        }

        @Option(names = { "-v", "--verbose" }, description = "verbose mode (same as --log=DEBUG)")
        void setVerbose(final boolean isVerbose) {
            if (isVerbose) {
                logLevel = Level.DEBUG;
            }
        }

        @Option(names = "--help", description = "show this help message and exit", usageHelp = true)
        boolean helpRequested;

        @Option(names = "--version", description = "print version information and exit", versionHelp = true)
        boolean versionInfoRequested;
    }

    @Mixin
    final Config conf = new Config();

    @Mixin
    private final RepositoryRewriter rewriter;

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

        try (final Repository readRepo = getInputRepository()) {
            log.debug("Input repository: {}", readRepo.getDirectory());
            try (final Repository writeRepo = getOutputRepository()) {
                if (writeRepo == null) {
                    rewriter.initialize(readRepo, readRepo);
                } else {
                    log.debug("Output repository: {}", writeRepo.getDirectory());
                    rewriter.initialize(readRepo, writeRepo);
                }

                final Instant start = Instant.now();
                log.info("Starting rewriting...");

                rewriter.rewrite();

                final Instant finish = Instant.now();
                log.info("Finished rewriting. Runtime: {} ms", Duration.between(start, finish).toMillis());
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }

        if (conf.commitMappingFile != null) {
            Try.io(() -> exportObject(rewriter.exportCommitMapping(), conf.commitMappingFile));
        }

        return 0;
    }

    /**
     * Returns the input repository object.
     */
    protected Repository getInputRepository() throws IOException {
        final File inputDir;
        if (conf.duplicatedOutput != null) {
            // duplicate mode
            final Path outputPath = conf.duplicatedOutput.toPath();

            // cleaning
            if (conf.isCleanEnabled && Files.exists(outputPath)) {
                deleteDirectory(outputPath);
            }

            copyDirectory(conf.input.toPath(), outputPath);
            inputDir = conf.duplicatedOutput;
        } else {
            inputDir = conf.input;
        }

        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        if (conf.isBare) {
            builder.setGitDir(inputDir).readEnvironment();
        } else {
            builder.findGitDir(inputDir);
        }
        return builder.build();
    }

    /**
     * Returns the output repository object. Returns null in overwrite mode.
     */
    protected Repository getOutputRepository() throws IOException {
        if (conf.duplicatedOutput != null) {
            // duplicate mode
            return null;
        }

        if (conf.output == null) {
            return null;
        }

        final File outputDir = conf.output;

        // cleaning
        final Path outputPath = outputDir.toPath();
        if (conf.isCleanEnabled && Files.exists(outputPath)) {
            deleteDirectory(outputPath);
        }

        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        if (conf.isBare) {
            builder.setGitDir(outputDir);
        } else {
            final File gitdbDir = new File(outputDir, Constants.DOT_GIT);
            builder.setGitDir(gitdbDir);
        }
        return builder.build();
    }

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
        final int status = new CommandLine(app).execute(args);
        System.exit(status);
    }
}
