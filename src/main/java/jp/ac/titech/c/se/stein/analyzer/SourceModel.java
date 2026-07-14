package jp.ac.titech.c.se.stein.analyzer;

import java.util.ArrayList;
import java.util.Collection;
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
     * The element's module body: its extent text with the given comments removed (spliced out,
     * whitespace normalized); passing none yields its full text. The default renders the element's
     * extent; this is the one point a backend deviates (JDT wraps a member so it parses standalone).
     *
     * @param e       the element to render
     * @param dropped the comments to remove from the body; the caller decides which
     */
    default String getModuleText(final Element e, final Collection<Fragment> dropped) {
        return FormatUtils.stripComments(e.getExtentFragment(), dropped);
    }

    /**
     * Every comment within the element's extent ({@link Element#getExtentFragment}), in source order, or
     * null when this backend has no notion of comments (a tag extractor). A backend that has comments
     * derives them from its own parse, scoped to the element, the same way it derives tokens; the default
     * is none.
     */
    default List<Fragment> getExtentComments(final Element e) {
        return null;
    }

    /**
     * The doc comments within the element's extent: those sitting in a declaration's attached-comment
     * region (its extent outside its core), where leading and trailing docs go. Classified over the whole
     * subtree, so a nested member's doc counts as a doc even inside an enclosing module. Null when the
     * backend has no notion of comments.
     */
    default List<Fragment> getDocComments(final Element e) {
        final List<Fragment> all = getExtentComments(e);
        if (all == null) {
            return null;
        }
        final List<int[]> regions = attachedRegions(e);
        return all.stream().filter(c -> isInRegion(regions, c.getBegin())).toList();
    }

    /**
     * The body comments within the element's extent: every comment that is not a {@link #getDocComments}.
     * Null when the backend has no notion of comments.
     */
    default List<Fragment> getBodyComments(final Element e) {
        final List<Fragment> all = getExtentComments(e);
        if (all == null) {
            return null;
        }
        final List<int[]> regions = attachedRegions(e);
        return all.stream().filter(c -> !isInRegion(regions, c.getBegin())).toList();
    }

    /**
     * The half-open character ranges, over the element's subtree, where a declaration's attached (leading
     * or trailing) comments sit: each element's extent outside its core. A comment falling in one is a doc.
     */
    private static List<int[]> attachedRegions(final Element e) {
        final List<int[]> regions = new ArrayList<>();
        final Fragment core = e.getCoreFragment();
        final Fragment extent = e.getExtentFragment();
        if (core != null && extent != null) {
            if (extent.getBegin() < core.getBegin()) {
                regions.add(new int[] {extent.getBegin(), core.getBegin()});
            }
            if (core.getEnd() < extent.getEnd()) {
                regions.add(new int[] {core.getEnd(), extent.getEnd()});
            }
        }
        for (final Element child : e.getChildren()) {
            regions.addAll(attachedRegions(child));
        }
        return regions;
    }

    /**
     * Whether the position falls within any of the ranges.
     */
    private static boolean isInRegion(final List<int[]> regions, final int pos) {
        for (final int[] r : regions) {
            if (pos >= r[0] && pos < r[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every comment in the file, as fragments in source order, or null when this backend has no notion
     * of comments. This is the file root's extent comments ({@link #getExtentComments}), which span the
     * whole file.
     */
    default List<Fragment> getComments() {
        return getExtentComments(getRoot());
    }
}
