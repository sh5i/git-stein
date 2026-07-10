package jp.ac.titech.c.se.stein.analyzer;

/**
 * The backend-neutral contract a consumer needs to decompose one source file into named elements and
 * recover each element's raw source text. This is the minimum an analyzer must provide; a
 * structure-only backend (for example one driven by a tag extractor) implements just this. A backend
 * that can also produce a typed token stream implements {@link TokenizingAnalyzer}, from which a
 * consumer builds its own token output (a FinerGit sequence, cregit's token-per-line format).
 */
public interface SourceAnalyzer {
    /**
     * Extracts the element tree: a {@link Element.Kind#FILE} root holding the file's declarations.
     */
    Element extract();

    /**
     * The raw source text of an element, rendered from its source span ({@link Element#getFragment}). A
     * backend that synthesizes text beyond the element's own source (e.g. wrapping a member so it parses
     * standalone) overrides this.
     */
    default String rawText(final Element e) {
        return e.getFragment().getWiderContent();
    }

    /**
     * The comment text attached to the element's declaration, for a Historage comment side file, or
     * null when this analyzer does not collect comments (the default). An empty string is a declaration
     * that has no comment, distinct from null meaning the analyzer has no notion of comments.
     */
    default String commentText(final Element e) {
        return null;
    }
}
