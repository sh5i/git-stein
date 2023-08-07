package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import lombok.ToString;
import picocli.CommandLine.Command;

@ToString
@Command(name = "identity", description = "Copy objects without transformation")
public class Identity extends RepositoryRewriter {
}
