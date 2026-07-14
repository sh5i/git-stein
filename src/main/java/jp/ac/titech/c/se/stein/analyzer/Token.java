package jp.ac.titech.c.se.stein.analyzer;

/**
 * A single leaf token of a parse: its text, its grammar-derived type (the same typing the FinerGit
 * token sequence uses), its 1-based start line and column, its character offset into the decoded
 * content, and two classification flags a consumer uses to assemble its own output. {@code comment} marks a comment
 * token (cregit keeps them; a FinerGit sequence skips them). {@code frame} marks one of the enclosing
 * declaration's frame delimiters -- the parentheses of its parameter list, the braces of its body, or
 * a bodyless declaration's terminating semicolon -- which a FinerGit sequence drops under Heuristic 2.
 * The {@code frame} flag is meaningful only in the element-scoped {@link TokenizingModel#getTokens};
 * the whole-file {@link TokenizingModel#walkTokens} leaves it false.
 */
public record Token(String text, String type, int line, int column, int start, boolean comment, boolean frame) {
    /**
     * A token with no comment or frame classification, for a consumer (such as cregit) that does not
     * need them.
     */
    public Token(final String text, final String type, final int line, final int column, final int start) {
        this(text, type, line, column, start, false, false);
    }
}
