package jp.ac.titech.c.se.stein.analyzer;

import java.util.List;

import jp.ac.titech.c.se.stein.analyzer.util.FormatUtils;
import jp.ac.titech.c.se.stein.core.SourceText.Fragment;

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

    /**
     * Every comment within the given element's extent ({@link Element#getExtentFragment}), as fragments
     * in source order, or null when this backend has no notion of comments (a tag extractor). A backend
     * that has comments derives them from its own parse, scoped to the element, the same way it derives
     * tokens; the default is none.
     */
    default List<Fragment> extentComments(final Element e) {
        return null;
    }

    /**
     * The comment text within the given element's extent ({@link #extentComments}), each comment
     * rendered de-indented, or null when the backend has no notion of comments. An empty string is a
     * declaration that has no comment, distinct from null.
     */
    default String commentText(final Element e) {
        final List<Fragment> comments = extentComments(e);
        if (comments == null) {
            return null;
        }
        final StringBuilder sb = new StringBuilder();
        for (final Fragment c : comments) {
            sb.append(FormatUtils.dedent(c.getWiderContent()));
        }
        return sb.toString();
    }

    /**
     * Every comment in the file, as fragments in source order, or null when this backend has no notion
     * of comments. This is the file root's extent comments ({@link #extentComments}), which span the
     * whole file.
     */
    default List<Fragment> comments() {
        return extentComments(getRoot());
    }
}
