package jp.ac.titech.c.se.stein;

import java.io.File;
import java.io.IOException;
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

    public static CommandLine parseOptions(final String[] args) {
        final Options opts = new Options();
        opts.addOption("c", "concurrent", false, "rewrite trees concurrently");
        opts.addOption("o", "output", true, "specify output repository path (non-overwrite mode)");
        opts.addOption("b", "bare", false, "treat the repository as a bare repository");
        opts.addOption("n", "dry-run", false, "don't actually write anything");
        opts.addOption(null, "level", true, "set log level (default: INFO)");
        opts.addOption("v", "verbose", false, "verbose mode (same as --log=trace)");
        opts.addOption("q", "quiet", false, "quiet mode (same as --log=error)");
        opts.addOption(null, "help", false, "print this help");

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

    public CLI(final Class<? extends RepositoryRewriter> rewriterClass, final String[] args) {
        this.rewriterClass = rewriterClass;
        this.cmd = parseOptions(args);
    }

    public CLI(final String className, final String[] args) {
        this(loadClass(className), args);
    }

    public void run() {
        setLoggerLevel();

        log.debug("Rewriter: {}", rewriterClass.getName());
        final RepositoryRewriter rewriter = newInstance(rewriterClass);

        try (final Repository readRepo = getInputRepository()) {
            log.debug("Repository: {}", readRepo.getDirectory());

            try (final Repository writeRepo = getOutputRepository()) {
                if (writeRepo == null) {
                    rewriter.initialize(readRepo);
                } else {
                    log.debug("Output repository: {}", writeRepo.getDirectory());
                    rewriter.initialize(readRepo, writeRepo);
                }

                if (cmd.hasOption("dry-run")) {
                    rewriter.setDryRunning(true);
                }

                if (cmd.hasOption("concurrent") && rewriter instanceof ConcurrentRepositoryRewriter) {
                    ((ConcurrentRepositoryRewriter) rewriter).setConcurrent(true);
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

    protected RepositoryRewriter newInstance(final Class<? extends RepositoryRewriter> klass) {
        try {
            return klass.newInstance();
        } catch (final InstantiationException | IllegalAccessException e) {
            log.error("Failed to load: {}", klass);
            throw new RuntimeException(e);
        }
    }

    /**
     * Returns the input repository object.
     */
    protected Repository getInputRepository() throws IOException {
        final File path = new File(cmd.getArgs()[0]);
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
        if (!cmd.hasOption("output")) {
            return null;
        }
        final File path = new File(cmd.getOptionValue("output"));
        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        if (cmd.hasOption("bare")) {
            builder.setGitDir(path);
        } else {
            final File gitdb = new File(path, Constants.DOT_GIT);
            builder.setGitDir(gitdb);
        }
        return builder.build();
    }
}
