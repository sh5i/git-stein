package jp.ac.titech.c.se.stein.analyzer;

/**
 * The backend-neutral contract a consumer needs to decompose one source file into named elements and
 * render each element's text. This is the minimum an analyzer must provide; a structure-only backend
 * (for example one driven by a tag extractor) implements just this, honoring only {@link
 * RenderOptions#RAW}. A backend that can also produce a typed token stream implements {@link
 * TokenizingAnalyzer}.
 */
public interface SourceAnalyzer {
    /**
     * Extracts the element tree: a {@link Element.Kind#FILE} root holding the file's declarations.
     */
    Element extract();

    /**
     * Renders an element to text per the given options (its raw source, or, for a tokenizing analyzer,
     * a token sequence).
     */
    String render(Element e, RenderOptions options);

    /**
     * The comment text attached to the element's declaration, for a Historage comment side file, or
     * null when this analyzer does not collect comments (the default). An empty string is a declaration
     * that has no comment, distinct from null meaning the analyzer has no notion of comments.
     */
    default String commentText(final Element e) {
        return null;
    }
}
