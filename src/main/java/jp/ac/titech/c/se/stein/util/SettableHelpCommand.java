package jp.ac.titech.c.se.stein.util;

import lombok.Setter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Help;
import picocli.CommandLine.IHelpCommandInitializable2;

import java.io.PrintWriter;
import java.util.Map;

/**
 * A duplicate of picocli.CommandLine.HelpCommand, but the command parameter settable from outside.
 * Useful for a command with subcommandsRepeatable = true.
 */
@Command(name = "help", description = "Display help information about the specified command",
         synopsisHeading = "%nUsage: ", helpCommand = true)
public class SettableHelpCommand implements IHelpCommandInitializable2, Runnable {
    @Setter
    private String command;
    private CommandLine self;
    private PrintWriter outWriter;
    private Help.ColorScheme colorScheme;

    @Override
    public void run() {
        CommandLine parent = self == null ? null : self.getParent();
        if (parent == null) { return; }
        if (command != null) {
            Map<String, CommandLine> parentSubcommands = parent.getCommandSpec().subcommands();
            CommandLine subcommand = parentSubcommands.get(command);
            if (subcommand != null) {
                subcommand.usage(outWriter, colorScheme);
            } else {
                throw new CommandLine.ParameterException(parent, "Unknown subcommand '" + command + "'.", null, command);
            }
        } else {
            parent.usage(outWriter, colorScheme);
        }
    }

    @Override
    public void init(CommandLine helpCommandLine, Help.ColorScheme colorScheme, PrintWriter out, PrintWriter err) {
        this.self = helpCommandLine;
        this.colorScheme = colorScheme;
        this.outWriter = out;
    }
}
