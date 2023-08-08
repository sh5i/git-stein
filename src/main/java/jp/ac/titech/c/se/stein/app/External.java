package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import jp.ac.titech.c.se.stein.util.Loader;
import lombok.ToString;
import picocli.CommandLine;
import picocli.CommandLine.*;

@ToString
@Command(name = "@external", description = "Run external rewriter")
public class External implements RepositoryRewriter.Factory {
    @Option(names = "--class", paramLabel = "<class>", description = "rewriter class")
    Class<? extends RepositoryRewriter> klass;

    @Option(names = "--args", split = " ", paramLabel="<args>", description = "rewriter arguments")
    String[] args;

    @Override
    public RepositoryRewriter create() {
        final RepositoryRewriter result = (RepositoryRewriter) Loader.newInstance(klass);
        new CommandLine(result)
                .setExpandAtFiles(false)
                .parseArgs(args);
        return result;
    }
}
