package jp.ac.titech.c.se.stein;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;

import jp.ac.titech.c.se.stein.core.Config;
import jp.ac.titech.c.se.stein.core.Try;

/**
 * A Config implementation using Apache Commons CLI
 */
public class CommonsConfig implements Config {

    private CommandLine cmd;

    private final Options opts = new Options();

    public CommonsConfig() {
    }

    @Override
    public void addOption(final String opt, final String longOpt, final boolean hasArg, final String description) {
        opts.addOption(opt, longOpt, hasArg, description);
    }

    @Override
    public boolean hasOption(final String opt) {
        return cmd.hasOption(opt);
    }

    @Override
    public String getOptionValue(final String opt) {
        return cmd.getOptionValue(opt);
    }

    public Options getOptions() {
        return opts;
    }

    public CommandLine getCommandLine() {
        return cmd;
    }

    public void run(final String[] args) {
        final CommandLineParser parser = new DefaultParser();
        cmd = Try.run(() -> parser.parse(opts, args));
    }

    public String[] getArgs() {
        return cmd.getArgs();
    }
}
