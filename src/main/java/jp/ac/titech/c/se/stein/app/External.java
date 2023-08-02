package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import jp.ac.titech.c.se.stein.util.Loader;
import picocli.CommandLine;
import picocli.CommandLine.*;

@Command(name = "external", aliases = {"ext"}, description = "Run external rewriter")
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
