package jp.ac.titech.c.se.stein.analyzer;

/**
 * The backend-neutral result of analyzing one source file: its named elements, with each element's
 * raw source text recoverable from the element itself ({@link Element#rawText}). This is the minimum
 * an analysis produces; a structure-only backend (for example one driven by a tag extractor) yields
 * just this. A backend that can also produce a typed token stream yields a {@link TokenizingModel},
 * from which a consumer builds its own token output (a FinerGit sequence, cregit's token-per-line
 * format).
 */
public interface SourceModel {
    /**
     * The extracted element tree: a {@link Element.Kind#FILE} root holding the file's declarations.
     */
    Element getRoot();

    /**
     * Renders one element as a Historage module body, with its attached comments inline or excluded.
     * The default is the element's own text ({@link Element#rawText}/{@link Element#coreText}); this is
     * the one point where a backend may deviate (JDT wraps a member so it parses standalone, and strips
     * every comment rather than only attached ones).
     */
    default String moduleText(final Element e, final boolean withComments) {
        return withComments ? e.rawText() : e.coreText();
    }
}
