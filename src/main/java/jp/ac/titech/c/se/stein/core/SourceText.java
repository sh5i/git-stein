package jp.ac.titech.c.se.stein.core;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.mozilla.universalchardet.UniversalDetector;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@Slf4j
public class SourceText {
    public final static Pattern LINE_BREAK = Pattern.compile("\n");

    @Getter
    protected final byte[] raw;

    @Getter
    protected final String content;

    protected int[] lineOffsets;

    public SourceText(final byte[] raw) {
        this.raw = raw;
        this.content = normalizeBreak(load(raw));
    }

    protected String load(final byte[] blob) {
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

    protected String guessCharset(final byte[] data) {
        final UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(data, 0, data.length);
        detector.dataEnd();
        return detector.getDetectedCharset();
    }

    protected String normalizeBreak(final String text) {
        return text.replaceAll("\r\n?", "\n");
    }

    public void prepareLineOffsets() {
        final Matcher matcher = LINE_BREAK.matcher(content);
        this.lineOffsets = IntStream.concat(IntStream.of(0), matcher.results().mapToInt(m -> m.start() + 1)).toArray();
    }

    public Fragment getFragment(final int beginIndex, final int endIndex) {
        return new Fragment(beginIndex, endIndex);
    }

    public Fragment getFragmentOfLines(final int beginLine, final int endLine) {
        int beginIndex = lineOffsets[beginLine - 1];
        int endIndex = endLine < lineOffsets.length ? lineOffsets[endLine] : content.length();
        return new Fragment(beginIndex, beginIndex, endIndex, endIndex);
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

        public String getExactContent() {
            return content.substring(begin, end);
        }

        public String getWiderContent() {
            final String result = content.substring(widerBegin, widerEnd);
            return (result.length() > 0 && result.charAt(result.length() - 1) == '\n') ? result : (result + "\n");
        }

        public String getIndent() {
            return content.substring(widerBegin, begin);
        }
    }
}
