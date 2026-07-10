package jp.ac.titech.c.se.stein.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mozilla.universalchardet.UniversalDetector;

/**
 * Decides the character encoding of raw source bytes and decodes them to a BOM-stripped string, by
 * either of two strategies: {@link #decode} uses statistical detection (via
 * {@link UniversalDetector}), preferring UTF-8; {@link #decodeWithMagicComment} honors the encoding
 * magic comment of languages that declare one (Python's PEP 263, Ruby's), defaulting to UTF-8.
 */
public final class SourceEncoding {
    private SourceEncoding() {
    }

    /**
     * An encoding magic comment on a single line, e.g. {@code # -*- coding: latin-1 -*-} (Python) or
     * {@code # encoding: euc-jp} (Ruby); {@code encoding} matches too, as it ends in {@code coding}.
     */
    private static final Pattern MAGIC_COMMENT = Pattern.compile("^[ \t\f]*#.*?coding[:=][ \t]*([-_.a-zA-Z0-9]+)");

    /**
     * Decodes undeclared source, preferring UTF-8: {@link UniversalDetector} is unreliable on valid UTF-8
     * that holds 4-byte characters (emoji), so bytes that decode strictly as UTF-8 are kept as such. The
     * exception is a byte stream containing an {@code ESC} (0x1B): a 7-bit escape-based encoding
     * (ISO-2022-JP/KR/CN) is valid UTF-8 yet not UTF-8 text, so it -- and non-UTF-8 bytes -- fall through
     * to statistical detection.
     */
    public static String decode(final byte[] data) {
        if (!hasIso2022Escape(data)) {
            final String utf8 = tryDecodeUtf8(data);
            if (utf8 != null) {
                return stripBom(utf8);
            }
        }
        return stripBom(new String(data, detectStatistically(data)));
    }

    /**
     * Decodes source whose encoding is declared in a magic comment (Python, Ruby).
     */
    public static String decodeWithMagicComment(final byte[] raw) {
        // strip a UTF-8 BOM so the magic-comment scan sees a clean first line
        final byte[] bytes = stripUtf8Bom(raw);
        return new String(bytes, detectFromMagicComment(bytes));
    }

    /**
     * Strictly decodes the bytes as UTF-8, returning the decoded string, or {@code null} when they are
     * not valid UTF-8.
     */
    public static String tryDecodeUtf8(final byte[] data) {
        final CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(data)).toString();
        } catch (final CharacterCodingException e) {
            return null;
        }
    }

    /**
     * Whether the bytes contain an ESC (0x1B), the marker of a 7-bit escape-based encoding
     * (ISO-2022-JP/KR/CN) -- valid UTF-8 yet not UTF-8 text.
     */
    private static boolean hasIso2022Escape(final byte[] data) {
        for (final byte b : data) {
            if (b == 0x1B) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detects the charset with {@link UniversalDetector}, falling back to UTF-8 when it cannot decide or
     * names a charset the JVM does not know.
     */
    public static Charset detectStatistically(final byte[] data) {
        final UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(data, 0, data.length);
        detector.dataEnd();
        final String name = detector.getDetectedCharset();
        if (name != null) {
            try {
                return Charset.forName(name);
            } catch (final IllegalArgumentException e) {
                // an unknown name falls through to UTF-8
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * The charset declared by an encoding magic comment in the first two lines, or UTF-8 when there is
     * none.
     */
    private static Charset detectFromMagicComment(final byte[] bytes) {
        final String head = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.ISO_8859_1);
        final String[] lines = head.split("\n", 3);
        // scan the first two lines. Strictly, Ruby honors a second-line comment only after a shebang, but
        // always reading it is a harmless simplification that keeps Python and Ruby on one path.
        for (int i = 0; i < 2 && i < lines.length; i++) {
            final Matcher m = MAGIC_COMMENT.matcher(lines[i]);
            if (m.find()) {
                return charsetForCodec(m.group(1));
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Resolves an encoding name to a Java charset, retrying with {@code -} and {@code _} removed
     * ({@code latin-1} is unknown to Java but {@code latin1} resolves to ISO-8859-1) and falling back to
     * UTF-8 if still unknown.
     */
    public static Charset charsetForCodec(final String name) {
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

    /**
     * Removes a leading UTF-8 byte-order mark ({@code EF BB BF}) from the bytes, if present.
     */
    public static byte[] stripUtf8Bom(final byte[] raw) {
        if (raw.length >= 3 && (raw[0] & 0xFF) == 0xEF && (raw[1] & 0xFF) == 0xBB && (raw[2] & 0xFF) == 0xBF) {
            return Arrays.copyOfRange(raw, 3, raw.length);
        }
        return raw;
    }

    /**
     * Strips a leading byte-order mark character (U+FEFF) from decoded content; a BOM is metadata, not
     * source text. The char-level counterpart to {@link #stripUtf8Bom}.
     */
    public static String stripBom(final String s) {
        return !s.isEmpty() && s.charAt(0) == 0xFEFF ? s.substring(1) : s;
    }
}
