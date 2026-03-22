package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import jp.ac.titech.c.se.stein.rewriter.RewriterCommand;
import jp.ac.titech.c.se.stein.util.Loader;
import lombok.ToString;
import picocli.CommandLine;
import picocli.CommandLine.*;

/**
 * Loads and runs an external {@link RepositoryRewriter} implementation by class name.
 * The rewriter class is instantiated and configured with the given arguments via PicoCLI.
 */
@ToString
@Command(name = "@external", description = "Run external rewriter")
public class External implements RewriterCommand {
    @Option(names = "--class", paramLabel = "<class>", description = "rewriter class")
    Class<? extends RepositoryRewriter> klass;

    @Option(names = "--args", split = " ", paramLabel="<args>", description = "rewriter arguments")
    String[] args;

    @Override
    public RepositoryRewriter toRewriter() {
        final RepositoryRewriter result = (RepositoryRewriter) Loader.newInstance(klass);
        if (args != null) {
            new CommandLine(result)
                    .setExpandAtFiles(false)
                    .parseArgs(args);
        }
        return result;
    }
}
