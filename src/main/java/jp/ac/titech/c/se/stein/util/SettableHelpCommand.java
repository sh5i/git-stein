/*
   Copyright 2017 Remko Popma

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */
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
