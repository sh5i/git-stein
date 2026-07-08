package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.ts.RenderOptions;
import lombok.ToString;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * A FinerGit-style generator: it reuses the extraction of {@link HistorageTreeSitter} for every
 * supported language, but renders each module's content as a FinerGit token sequence (one token per
 * line, annotated with a type — Heuristic 1) instead of the raw source, and omits a method's frame
 * tokens (Heuristic 2). Classes are naming scopes only.
 *
 * <p>The structural tokens (brackets, parentheses, semicolons) are typed from their syntactic
 * context generically for all languages, and identifiers are typed from the grammar field they fill
 * (a declared name, an invoked callee, or a plain variable).</p>
 *
 * @see <a href="https://github.com/kusumotolab/FinerGit">FinerGit</a>
 */
@ToString
@Command(name = "@finer", description = "Generate FinerGit-style token-sequence files via tree-sitter")
public class Finer extends HistorageTreeSitter {
    @Option(names = "--token-type", negatable = true,
            description = "annotate each token with its type (FinerGit Heuristic 1)")
    protected boolean includesTokenType = true;

    @Option(names = "--omit-frame", negatable = true,
            description = "omit each method's parameter parentheses and body braces (FinerGit Heuristic 2)")
    protected boolean omitsFrame = true;

    /**
     * Classes are naming scopes only in FinerGit; no class files are emitted.
     */
    @Override
    protected boolean wantsClasses() {
        return false;
    }

    @Override
    protected RenderOptions renderOptions() {
        return new RenderOptions(true, includesTokenType, omitsFrame);
    }
}
