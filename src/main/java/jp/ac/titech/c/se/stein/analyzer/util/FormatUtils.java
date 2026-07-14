package jp.ac.titech.c.se.stein.analyzer.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import jp.ac.titech.c.se.stein.core.SourceText.Fragment;

/**
 * Text-formatting helpers for rendering extracted source.
 */
public final class FormatUtils {
    private FormatUtils() {
    }

    /**
     * The region's widened text with the given comments removed and whitespace normalized. A comment
     * alone on its line(s) takes the whole line (indent and trailing newline), so no blank line is left;
     * a comment sharing a line with code is removed with the horizontal whitespace it faced (code on both
     * sides collapses to a single space, code on one side is closed up against it, a trailing comment's
     * line keeps only its newline).
     *
     * @param region   the fragment whose widened text is stripped; its offset maps each comment's
     *                 absolute range into that text
     * @param comments the comments to remove, in any order
     * @return the region's text without the comments
     */
    public static String stripComments(final Fragment region, final Collection<Fragment> comments) {
        final String content = region.getWiderContent();
        final int base = region.getWiderBegin();
        final List<Fragment> sorted = new ArrayList<>(comments);
        sorted.sort(Comparator.comparingInt(Fragment::getBegin));
        final int n = content.length();
        final StringBuilder sb = new StringBuilder();
        int pos = 0;
        for (final Fragment comment : sorted) {
            final int begin = comment.getBegin() - base;
            int end = comment.getEnd() - base;
            // a line comment's fragment may include its terminating newline; never cut it here
            while (end > begin && (content.charAt(end - 1) == '\n' || content.charAt(end - 1) == '\r')) {
                end--;
            }
            final int lineStart = content.lastIndexOf('\n', begin - 1) + 1;
            int nextBreak = end;
            while (nextBreak < n && content.charAt(nextBreak) != '\n') {
                nextBreak++;
            }
            final boolean codeBefore = !content.substring(lineStart, begin).isBlank();
            final boolean codeAfter = !content.substring(end, nextBreak).isBlank();

            final int cutStart;
            final int cutEnd;
            boolean space = false;
            if (!codeBefore && !codeAfter) {
                // the comment owns its line(s): remove the indent, the comment, and the newline
                cutStart = lineStart;
                cutEnd = nextBreak < n ? nextBreak + 1 : n;
            } else {
                int start = begin;
                if (codeBefore) {
                    while (start > lineStart && isHorizontalSpace(content.charAt(start - 1))) {
                        start--;
                    }
                }
                int stop = end;
                if (codeAfter) {
                    while (stop < n && isHorizontalSpace(content.charAt(stop))) {
                        stop++;
                    }
                } else {
                    stop = nextBreak;  // absorb the trailing whitespace, keep the newline
                }
                cutStart = start;
                cutEnd = stop;
                space = codeBefore && codeAfter;
            }
            if (cutStart > pos) {
                sb.append(content, pos, cutStart);
            }
            if (space) {
                sb.append(' ');
            }
            pos = Math.max(pos, cutEnd);
        }
        if (pos < n) {
            sb.append(content, pos, n);
        }
        return sb.toString();
    }

    /**
     * Whether the character is an in-line space or tab (not a line break).
     */
    private static boolean isHorizontalSpace(final char ch) {
        return ch == ' ' || ch == '\t';
    }

    /**
     * Strips the leading-whitespace prefix common to all non-blank lines, so an indented block is left
     * flush against the margin. The prefix is compared character by character, so mixed spaces and tabs
     * are shared only where they actually agree.
     */
    public static String dedent(final String text) {
        final String[] lines = text.split("\n", -1);
        String common = null;
        for (final String line : lines) {
            if (line.isBlank()) {
                continue;
            }
            int i = 0;
            while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
                i++;
            }
            common = common == null ? line.substring(0, i) : commonPrefix(common, line.substring(0, i));
            if (common.isEmpty()) {
                return text;
            }
        }
        if (common == null) {
            return text;
        }
        final StringBuilder sb = new StringBuilder();
        for (int k = 0; k < lines.length; k++) {
            if (k > 0) {
                sb.append("\n");
            }
            final String line = lines[k];
            sb.append(line.startsWith(common) ? line.substring(common.length()) : line);
        }
        return sb.toString();
    }

    private static String commonPrefix(final String a, final String b) {
        final int n = Math.min(a.length(), b.length());
        int i = 0;
        while (i < n && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return a.substring(0, i);
    }
}
