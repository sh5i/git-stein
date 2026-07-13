package jp.ac.titech.c.se.stein.app.blob;

import java.util.List;

import jp.ac.titech.c.se.stein.analyzer.Language;
import jp.ac.titech.c.se.stein.analyzer.SourceModel;
import jp.ac.titech.c.se.stein.analyzer.TreeSitterAnalyzer;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.core.SourceText.Fragment;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

/**
 * Removes every comment from a source file, a source-code normalization across all tree-sitter
 * languages. Comments are located by the parse tree (not a regex), so a {@code //} inside a string is
 * never mistaken for one, and comments inside a method body are removed too, not only the ones attached
 * to a declaration.
 *
 * <p>Whitespace is normalized as it goes: a comment alone on its line(s) is removed together with that
 * line's indentation and trailing newline, so no blank line is left behind; a comment sharing a line
 * with code is removed with the horizontal whitespace it faced, collapsing to a single space between
 * code on both sides. Line breaks are normalized to {@code \n}.</p>
 *
 * <p>The whitespace-normalization rules follow preform's Kotlin {@code CommentRemover}, generalized
 * from Java to every tree-sitter language.</p>
 *
 * @see <a href="https://github.com/xecua/preform/blob/main/src/main/kotlin/page/caffeine/preform/filter/normalizer/CommentRemover.kt">preform's CommentRemover.kt</a>
 */
@Slf4j
@ToString
@Command(name = "@strip-comments", description = "Remove comments from source files")
public class StripComments implements BlobTranslator {
    private final TreeSitterAnalyzer analyzer = new TreeSitterAnalyzer();

    @Mixin
    private final NameFilter filter = new NameFilter();

    @Override
    public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
        if (!filter.accept(entry)) {
            return entry;
        }
        final Language language = analyzer.languageOf(entry.getName());
        if (language == null) {
            return entry;  // not a language we can parse
        }
        final SourceModel model = analyzer.analyze(entry.getName(), entry.getBlob(), c);
        if (model == null) {
            return entry;
        }
        final List<Fragment> comments = model.comments();
        if (comments == null || comments.isEmpty()) {
            return entry;
        }
        // the same decoded, line-normalized content the fragments index into
        final String content = SourceText.ofNormalized(entry.getBlob(), language::decode).getContent();
        return entry.update(strip(content, comments));
    }

    /**
     * Removes the given comment fragments from the content, normalizing whitespace. A comment alone on
     * its line(s) takes the whole line (indent and trailing newline), so no blank line is left. A
     * comment sharing a line with code is removed with the horizontal whitespace it faced: code on both
     * sides collapses to a single space (the comment was a token separator), code on one side is closed
     * up against it, and a trailing comment's line keeps only its newline.
     */
    static String strip(final String content, final List<Fragment> comments) {
        final int n = content.length();
        final StringBuilder sb = new StringBuilder();
        int pos = 0;
        for (final Fragment comment : comments) {
            final int begin = comment.getBegin();
            int end = comment.getEnd();
            // a line comment's node may include its terminating newline; never cut it here
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

    private static boolean isHorizontalSpace(final char ch) {
        return ch == ' ' || ch == '\t';
    }
}
