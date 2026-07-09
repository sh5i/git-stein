package jp.ac.titech.c.se.stein.analyzer;

/**
 * How {@link TreeSitterAnalyzer#render} turns an element into text: either its raw source, or a
 * FinerGit-style token sequence (one token per line). When it tokenizes, it may annotate each token
 * with its type (FinerGit Heuristic 1) and omit a method's frame tokens (FinerGit Heuristic 2).
 */
public record RenderOptions(boolean tokenizes, boolean includesType, boolean omitsFrame) {
    /**
     * Renders the raw source, unchanged.
     */
    public static final RenderOptions RAW = new RenderOptions(false, false, false);
}
