package jp.ac.titech.c.se.stein.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Decodes Python source the way Python defines it: UTF-8 by default (PEP 3120), overridden by
 * a PEP 263 coding declaration in the first two lines. Charset detection heuristics are
 * deliberately not used; they misread UTF-8 files that contain a few multibyte characters.
 */
public final class PythonSource {
    private PythonSource() {}

    /**
     * A PEP 263 coding declaration, e.g. {@code # -*- coding: latin-1 -*-}.
     */
    private static final Pattern CODING_DECLARATION = Pattern.compile("^[ \t\f]*#.*?coding[:=][ \t]*([-_.a-zA-Z0-9]+)");

    /**
     * Decodes raw Python source bytes into a {@link SourceText} with normalized line breaks.
     */
    public static SourceText decode(final byte[] raw) {
        byte[] bytes = raw;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            // drop the UTF-8 BOM
            bytes = Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        return SourceText.ofNormalized(bytes, detectCharset(bytes));
    }

    protected static Charset detectCharset(final byte[] bytes) {
        final String head = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.ISO_8859_1);
        final String[] lines = head.split("\n", 3);
        for (int i = 0; i < 2 && i < lines.length; i++) {
            final Matcher m = CODING_DECLARATION.matcher(lines[i]);
            if (m.find()) {
                return charsetForCodec(m.group(1));
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Resolves a Python codec name to a Java charset. Python aliases such as {@code latin-1} are
     * not always Java aliases, so an unresolved name is retried with hyphens and underscores
     * removed; a codec Java does not know at all falls back to UTF-8.
     */
    protected static Charset charsetForCodec(final String name) {
        try {
            return Charset.forName(name);
        } catch (final IllegalArgumentException e) {
            try {
                return Charset.forName(name.replaceAll("[-_]", ""));
            } catch (final IllegalArgumentException e2) {
                return StandardCharsets.UTF_8;
            }
        }
    }
}
