package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import picocli.CommandLine.Command;

@Command(name = "Identity", description = "Copy objects without transformation")
public class Identity extends RepositoryRewriter {
    public static void main(final String[] args) {
        Application.execute(new Identity(), args);
    }
}
