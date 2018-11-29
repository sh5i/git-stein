package jp.ac.titech.c.se.stein;

import java.io.IOException;
import java.util.Arrays;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Repository;
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
        opts.addOption("c", "concurrent", false, "Rewrite trees concurrently");
        opts.addOption(null, "level", true, "set log level (default: INFO)");
        opts.addOption("v", "verbose", false, "verbose mode (same as --log=trace)");
        opts.addOption("q", "quiet", false, "quiet mode (same as --log=error)");
        opts.addOption(null, "help", false, "print this help");

        CommandLine cmd = null;
        try {
            final CommandLineParser parser = new DefaultParser();
            cmd = parser.parse(opts, args);
            if (cmd.hasOption("help") || args.length == 0) {
                new HelpFormatter().printHelp("[options] files", opts);
                System.exit(0);
            }
        } catch (final ParseException e) {
            e.printStackTrace();
            System.exit(1);
        }
        return cmd;
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

        final String path = cmd.getArgs()[0];
        try {
            try (final Repository repo = new FileRepository(path)) {
                log.debug("Repository: {}", repo.getDirectory());
                rewriter.initialize(repo);
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
}
