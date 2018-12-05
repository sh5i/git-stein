package jp.ac.titech.c.se.stein;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;

public class CLI {
    private static final Logger log = LoggerFactory.getLogger(CLI.class);

    private final Class<? extends RepositoryRewriter> rewriterClass;

    private final RepositoryRewriter rewriter;

    private final CommandLine cmd;

    public static void main(final String[] args) {
        final String className = args[0];
        final String[] realArgs = Arrays.copyOfRange(args, 1, args.length);
        new CLI(className, realArgs).run();
    }

    public static void setLoggerLevel(final Level level) {
        final ch.qos.logback.classic.Logger rootLog = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLog.setLevel(level);
        log.debug("Set log level to {}", level);
    }

    public static CommandLine parseOptions(final String[] args, final RepositoryRewriter rewriter) {
        final Options opts = new Options();
        opts.addOption("o", "output", true, "specify output path (non-overwrite)");
        opts.addOption("d", "output-dup", true, "specify output path (duplicate-and-overwrite)");
        opts.addOption("b", "bare", false, "treat the repository as a bare repository");
        opts.addOption(null, "level", true, "set log level (default: INFO)");
        opts.addOption("v", "verbose", false, "verbose mode (same as --log=trace)");
        opts.addOption("q", "quiet", false, "quiet mode (same as --log=error)");
        opts.addOption(null, "help", false, "print this help");
        if (rewriter instanceof Configurable) {
            ((Configurable) rewriter).addOptions(opts);
        }

        try {
            final CommandLineParser parser = new DefaultParser();
            final CommandLine result = parser.parse(opts, args);
            if (result.hasOption("help") || args.length == 0) {
                new HelpFormatter().printHelp("[options] path/to/repo", opts);
                System.exit(result.hasOption("help") ? 0 : 1);
            }
            return result;
        } catch (final ParseException e) {
            throw new RuntimeException(e);
        }
    }

    public static Class<? extends RepositoryRewriter> loadClass(final String className) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends RepositoryRewriter> result = (Class<? extends RepositoryRewriter>) Class.forName(className);
            return result;
        } catch (final ClassNotFoundException e) {
            log.error("Failed to load: {}", className);
            throw new RuntimeException(e);
        }
    }

    public static RepositoryRewriter newInstance(final Class<? extends RepositoryRewriter> klass) {
        try {
            return klass.newInstance();
        } catch (final InstantiationException | IllegalAccessException e) {
            log.error("Failed to load: {}", klass);
            throw new RuntimeException(e);
        }
    }

    public CLI(final Class<? extends RepositoryRewriter> rewriterClass, final String[] args) {
        this.rewriterClass = rewriterClass;
        this.rewriter = newInstance(rewriterClass);
        this.cmd = parseOptions(args, this.rewriter);
    }

    public CLI(final String className, final String[] args) {
        this(loadClass(className), args);
    }

    public void run() {
        setLoggerLevel();
        log.debug("Rewriter: {}", rewriterClass.getName());

        if (rewriter instanceof Configurable) {
            ((Configurable) rewriter).configure(cmd);
        }

        try (final Repository readRepo = getInputRepository()) {
            log.debug("Input repository: {}", readRepo.getDirectory());
            try (final Repository writeRepo = getOutputRepository()) {
                if (writeRepo == null) {
                    rewriter.initialize(readRepo);
                } else {
                    log.debug("Output repository: {}", writeRepo.getDirectory());
                    rewriter.initialize(readRepo, writeRepo);
                }
                rewriter.rewrite();
            }
        } catch (final IOException e) {
            e.printStackTrace();
        }
    }

    protected void setLoggerLevel() {
        if (cmd.hasOption("level")) {
            setLoggerLevel(Level.valueOf(cmd.getOptionValue("level")));
        } else if (cmd.hasOption("verbose")) {
            setLoggerLevel(Level.TRACE);
        } else if (cmd.hasOption("quiet")) {
            setLoggerLevel(Level.ERROR);
        } else {
            setLoggerLevel(Level.INFO);
        }
    }

    /**
     * Returns the input repository object.
     */
    protected Repository getInputRepository() throws IOException {
        final File path;
        if (cmd.hasOption("output-dup")) {
            // duplicate mode
            final String target = cmd.getOptionValue("output-dup");
            copyDirectory(Paths.get(cmd.getArgs()[0]), Paths.get(target));
            path = new File(target);
        } else {
            path = new File(cmd.getArgs()[0]);
        }

        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        if (cmd.hasOption("bare")) {
            builder.setGitDir(path).readEnvironment();
        } else {
            builder.findGitDir(path);
        }
        return builder.build();
    }

    /**
     * Returns the output repository object. Returns null in overwrite mode.
     */
    protected Repository getOutputRepository() throws IOException {
        if (cmd.hasOption("output-dup")) {
            // duplicate mode
            return null;
        } else if (cmd.hasOption("output")) {
            final File path = new File(cmd.getOptionValue("output"));
            final FileRepositoryBuilder builder = new FileRepositoryBuilder();
            if (cmd.hasOption("bare")) {
                builder.setGitDir(path);
            } else {
                final File gitdb = new File(path, Constants.DOT_GIT);
                builder.setGitDir(gitdb);
            }
            return builder.build();
        } else {
            return null;
        }
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
}
