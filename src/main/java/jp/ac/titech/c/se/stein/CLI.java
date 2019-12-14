package jp.ac.titech.c.se.stein;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;

import org.apache.commons.cli.HelpFormatter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

import ch.qos.logback.classic.Level;
import jp.ac.titech.c.se.stein.core.Configurable;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;

public class CLI {
    private static final Logger log = LoggerFactory.getLogger(CLI.class);

    private final Class<? extends RepositoryRewriter> rewriterClass;

    private final RepositoryRewriter rewriter;

    private final CommonsConfig conf;

    public static void main(final String[] args) {
        final String className = args[0];
        final String[] realArgs = Arrays.copyOfRange(args, 1, args.length);
        new CLI(className, realArgs).run();
    }

    public static void setLoggerLevel(final String name, final Level level) {
        final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name);
        logger.setLevel(level);
        log.debug("Set log level of {} to {}", name, level);
    }

    public static CommonsConfig parseOptions(final String[] args, final RepositoryRewriter rewriter) {
        final CommonsConfig conf = new CommonsConfig();
        conf.addOption("o", "output", true, "specify output path");
        conf.addOption("d", "output-dup", true, "specify output path (duplicate-and-overwrite)");
        conf.addOption("q", "quiet", false, "quiet mode (same as --log=error)");
        conf.addOption("v", "verbose", false, "verbose mode (same as --log=debug)");
        conf.addOption(null, "bare", false, "treat the repository as a bare repository");
        conf.addOption(null, "clean", false, "delete the output beforehand if it exists");
        conf.addOption(null, "commit-mapping", true, "specify a file for dumping the commit mapping");
        conf.addOption(null, "level", true, "set log level (default: INFO)");
        conf.addOption(null, "help", false, "print this help");
        if (rewriter instanceof Configurable) {
            ((Configurable) rewriter).addOptions(conf);
        }

        conf.run(args);
        if (conf.hasOption("help") || args.length == 0) {
            new HelpFormatter().printHelp("[options] path/to/repo", conf.getOptions());
            System.exit(conf.hasOption("help") ? 0 : 1);
        }
        return conf;
    }

    protected static Class<? extends RepositoryRewriter> tryLoadClass(final String name) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends RepositoryRewriter> result = (Class<? extends RepositoryRewriter>) Class.forName(name);
            log.debug("Try loading class {}... succeeded.", name);
            return result;
        } catch (final ClassNotFoundException e) {
            log.debug("Try loading class {}... not found.", name);
            return null;
        } catch (final ClassCastException e) {
            log.debug("Try loading class {}... succeeded but was not a proper class.", name);
            return null;
        }
    }

    public static Class<? extends RepositoryRewriter> loadClass(final String key) {
        Class<? extends RepositoryRewriter> result = tryLoadClass(key);
        if (result == null) {
            result = tryLoadClass(CLI.class.getPackage().getName() + ".app." + key);
        }
        if (result == null) {
            result = tryLoadClass(CLI.class.getPackage().getName() + "." + key);
        }
        return result;
    }

    public static RepositoryRewriter newInstance(final Class<? extends RepositoryRewriter> klass) {
        try {
            return klass.newInstance();
        } catch (final InstantiationException | IllegalAccessException e) {
            log.error("Failed to load instance from class {}", klass);
            throw new RuntimeException(e);
        }
    }

    public CLI(final Class<? extends RepositoryRewriter> rewriterClass, final String[] args) {
        this.rewriterClass = rewriterClass;
        this.rewriter = newInstance(rewriterClass);
        this.conf = parseOptions(args, this.rewriter);
    }

    public CLI(final String className, final String[] args) {
        this(loadClass(className), args);
    }

    public void run() {
        final Level level = getLoggerLevel();
        setLoggerLevel(Logger.ROOT_LOGGER_NAME, getLoggerLevel());
        if (level == Level.DEBUG) {
            // suppress jgit's log
            setLoggerLevel("org.eclipse.jgit", Level.INFO);
        }

        log.debug("Rewriter: {}", rewriterClass.getName());

        if (rewriter instanceof Configurable) {
            ((Configurable) rewriter).configure(conf);
        }

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

        if (conf.hasOption("commit-mapping")) {
            final String filename = conf.getOptionValue("commit-mapping");
            try {
                exportObject(rewriter.exportCommitMapping(), filename);
            } catch (final IOException e) {
                e.printStackTrace();
            }
        }

    }

    protected Level getLoggerLevel() {
        if (conf.hasOption("level")) {
            return Level.valueOf(conf.getOptionValue("level"));
        } else if (conf.hasOption("verbose")) {
            return Level.DEBUG;
        } else if (conf.hasOption("quiet")) {
            return Level.ERROR;
        } else {
            return Level.INFO;
        }
    }

    /**
     * Returns the input repository object.
     */
    protected Repository getInputRepository() throws IOException {
        final File inputDir;
        if (conf.hasOption("output-dup")) {
            // duplicate mode
            final String output = conf.getOptionValue("output-dup");
            final Path outputPath = Paths.get(output);

            // cleaning
            if (conf.hasOption("clean") && Files.exists(outputPath)) {
                deleteDirectory(outputPath);
            }

            copyDirectory(Paths.get(conf.getArgs()[0]), outputPath);
            inputDir = new File(output);
        } else {
            inputDir = new File(conf.getArgs()[0]);
        }

        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        if (conf.hasOption("bare")) {
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
        if (conf.hasOption("output-dup")) {
            // duplicate mode
            return null;
        }

        if (!conf.hasOption("output")) {
            return null;
        }

        final File outputDir = new File(conf.getOptionValue("output"));

        // cleaning
        final Path outputPath = Paths.get(outputDir.toString());
        if (conf.hasOption("clean") && Files.exists(outputPath)) {
            deleteDirectory(outputPath);
        }

        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        if (conf.hasOption("bare")) {
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
    protected void exportObject(final Object object, final String filename) throws IOException {
        final Gson gson = new Gson();
        Files.write(Paths.get(filename), gson.toJson(object).getBytes());
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

}
