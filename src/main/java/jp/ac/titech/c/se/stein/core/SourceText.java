package jp.ac.titech.c.se.stein.core;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.mozilla.universalchardet.UniversalDetector;

import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * A decoded view of a raw blob, providing character-level access and fragment extraction.
 *
 * <p>The raw bytes are decoded to a string using charset detection (via
 * {@link UniversalDetector}), falling back to UTF-8. {@link Fragment} allows extracting
 * a substring along with its surrounding whitespace context (indent and trailing spaces).</p>
 */
@Slf4j
@RequiredArgsConstructor
public class SourceText {
    public final static Pattern LINE_BREAK = Pattern.compile("\n");

    /**
     * The original raw bytes.
     */
    @Getter
    protected final byte[] raw;

    /**
     * The decoded string content.
     */
    @Getter
    protected final String content;

    /**
     * Lazily computed offsets of each line start within {@link #content}.
     */
    protected int[] lineOffsets;

    /**
     * Lazily computed map from UTF-8 byte offsets of {@link #content} to char indices,
     * or {@code null} after preparation if the content is pure ASCII (offsets coincide).
     */
    protected int[] utf8Offsets;

    protected boolean utf8OffsetsPrepared;

    /**
     * Creates a {@link SourceText} from raw bytes, decoding with charset detection.
     */
    public static SourceText of(final byte[] raw) {
        return new SourceText(raw, load(raw));
    }

    /**
     * Creates a {@link SourceText} from raw bytes, normalizing line breaks to {@code \n}.
     */
    public static SourceText ofNormalized(final byte[] raw) {
        return new SourceText(raw, normalizeBreaks(load(raw)));
    }

    /**
     * Creates a {@link SourceText} from raw bytes with the given charset, normalizing line breaks
     * to {@code \n}.
     */
    public static SourceText ofNormalized(final byte[] raw, final Charset charset) {
        return new SourceText(raw, normalizeBreaks(new String(raw, charset)));
    }

    /**
     * Decodes raw bytes to a string using charset detection, falling back to UTF-8.
     */
    protected static String load(final byte[] blob) {
        final String charset = guessCharset(blob);
        if (charset != null) {
            try {
                return new String(blob, charset);
            } catch (final UnsupportedEncodingException e) {
                log.error(e.getMessage(), e);
            }
        }
        return new String(blob, StandardCharsets.UTF_8);
    }

    /**
     * Guesses the charset of the given data, or returns {@code null} if unknown.
     */
    protected static String guessCharset(final byte[] data) {
        final UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(data, 0, data.length);
        detector.dataEnd();
        return detector.getDetectedCharset();
    }

    /**
     * Normalizes {@code \r\n} and {@code \r} to {@code \n}.
     */
    protected static String normalizeBreaks(final String text) {
        return text.replaceAll("\r\n?", "\n");
    }

    /**
     * Lazily computes line offsets if not yet prepared.
     */
    protected void prepareLineOffsets() {
        if (this.lineOffsets == null) {
            final Matcher matcher = LINE_BREAK.matcher(content);
            this.lineOffsets = IntStream.concat(IntStream.of(0), matcher.results().mapToInt(m -> m.start() + 1)).toArray();
        }
    }

    /**
     * Maps a byte offset in the UTF-8 encoding of the content to a char index, for tools that
     * address the content by UTF-8 byte offsets (e.g., tree-sitter).
     */
    public int toCharIndex(final int utf8Offset) {
        prepareUtf8Offsets();
        return utf8Offsets != null ? utf8Offsets[utf8Offset] : utf8Offset;
    }

    /**
     * Lazily computes the UTF-8 offset map if not yet prepared.
     */
    protected void prepareUtf8Offsets() {
        if (utf8OffsetsPrepared) {
            return;
        }
        utf8OffsetsPrepared = true;
        final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == content.length()) {
            return; // pure ASCII: identity
        }
        final int[] map = new int[bytes.length + 1];
        int charIndex = 0;
        int byteOffset = 0;
        while (byteOffset < bytes.length) {
            final int b = bytes[byteOffset] & 0xFF;
            final int byteLength = b < 0x80 ? 1 : b < 0xE0 ? 2 : b < 0xF0 ? 3 : 4;
            final int charLength = byteLength == 4 ? 2 : 1; // beyond BMP: a surrogate pair
            for (int i = 0; i < byteLength; i++) {
                map[byteOffset + i] = charIndex;
            }
            byteOffset += byteLength;
            charIndex += charLength;
        }
        map[bytes.length] = charIndex;
        this.utf8Offsets = map;
    }

    /**
     * Returns a fragment for the given character index range.
     * The wider range (including surrounding whitespace) is computed automatically.
     */
    public Fragment getFragment(final int beginIndex, final int endIndex) {
        return new Fragment(beginIndex, endIndex);
    }

    /**
     * Returns a fragment spanning the given line range (1-based, inclusive).
     */
    public Fragment getFragmentOfLines(final int beginLine, final int endLine) {
        prepareLineOffsets();
        int beginIndex = lineOffsets[beginLine - 1];
        int endIndex = endLine < lineOffsets.length ? lineOffsets[endLine] : content.length();
        return new Fragment(beginIndex, endIndex, beginIndex, endIndex);
    }

    /**
     * Computes the length of the leading spaces.
     */
    protected int computeLeadingSpaces(final int beginIndex) {
        int result = 0;
        LOOP: while (beginIndex > result) {
            switch (content.charAt(beginIndex - result - 1)) {
                case ' ':
                case '\t':
                    result++;
                    continue;
                case '\r':
                case '\n':
                    break LOOP;
                default:
                    return 0;
            }
        }
        return result;
    }

    /**
     * Computes the length of the trailing spaces.
     */
    protected int computeTrailingSpaces(final int endIndex) {
        int result = 0;
        LOOP: while (endIndex + result < content.length()) {
            switch (content.charAt(endIndex + result)) {
                case ' ':
                case '\t':
                case '\r':
                    result++;
                    continue;
                case '\n':
                    result++;
                    break LOOP;
                default:
                    return 0;
            }
        }
        return result;
    }

    /**
     * A substring range within the enclosing {@link SourceText}.
     *
     * <p>Each fragment has an exact range ({@code begin}..{@code end}) and a wider range
     * ({@code widerBegin}..{@code widerEnd}) that includes surrounding whitespace.
     * The wider range extends backward over leading spaces/tabs and forward over
     * trailing spaces/tabs up to and including the next newline.</p>
     */
    @AllArgsConstructor
    public class Fragment {
        @Getter
        private final int begin;

        @Getter
        private final int end;

        @Getter
        private final int widerBegin;

        @Getter
        private final int widerEnd;

        public Fragment(final int begin, final int end) {
            this(begin, end, begin - computeLeadingSpaces(begin), end + computeTrailingSpaces(end));
        }

        @Override
        public String toString() {
            return getExactContent();
        }

        /**
         * Returns the content of the exact range.
         */
        public String getExactContent() {
            return content.substring(begin, end);
        }

        /**
         * Returns the content of the wider range, ensuring it ends with a newline.
         */
        public String getWiderContent() {
            final String result = content.substring(widerBegin, widerEnd);
            return (result.length() > 0 && result.charAt(result.length() - 1) == '\n') ? result : (result + "\n");
        }

        /**
         * Returns the leading whitespace before the exact range (i.e., the indent).
         */
        public String getIndent() {
            return content.substring(widerBegin, begin);
        }
    }
}
