package jp.ac.titech.c.se.stein.util;

/**
 * Text-formatting helpers for rendering extracted source.
 */
public final class FormatUtils {
    private FormatUtils() {
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
