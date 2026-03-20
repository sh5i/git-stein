package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import lombok.ToString;
import picocli.CommandLine.Command;

/**
 * A no-op rewriter that copies all objects without transformation.
 * Useful for verifying that the rewriting pipeline preserves repository content.
 */
@ToString
@Command(name = "@id", description = "Copy objects without transformation")
public class Identity extends RepositoryRewriter {
}
