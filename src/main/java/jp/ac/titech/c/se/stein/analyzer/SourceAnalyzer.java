package jp.ac.titech.c.se.stein.analyzer;

import jp.ac.titech.c.se.stein.core.SourceText.Fragment;
import jp.ac.titech.c.se.stein.util.FormatUtils;

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
     * The raw source text of an element with its attached comments ({@link Element#getExtentFragment}),
     * for a module rendered with comments inline. A backend that synthesizes text beyond the element's own
     * source (e.g. wrapping a member so it parses standalone) overrides this.
     */
    default String rawText(final Element e) {
        return e.getExtentFragment().getWiderContent();
    }

    /**
     * The raw source text of an element without its attached comments ({@link Element#getCoreFragment}),
     * for a module whose comments are separated into a side file.
     */
    default String coreText(final Element e) {
        return e.getCoreFragment().getWiderContent();
    }

    /**
     * The comment text attached to the element's declaration, for a Historage comment side file: its
     * attached comments ({@link Element#getComments}), each rendered de-indented, or null when this
     * analyzer has no notion of comments. An empty string is a declaration that has no comment, distinct
     * from null.
     */
    default String commentText(final Element e) {
        if (e.getComments() == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        for (final Fragment c : e.getComments()) {
            sb.append(FormatUtils.dedent(c.getWiderContent()));
        }
        return sb.toString();
    }
}
