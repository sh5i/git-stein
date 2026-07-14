package jp.ac.titech.c.se.stein.app.blob;

import java.util.List;

import jp.ac.titech.c.se.stein.analyzer.SourceModel;
import jp.ac.titech.c.se.stein.analyzer.TreeSitterAnalyzer;
import jp.ac.titech.c.se.stein.analyzer.util.FormatUtils;
import jp.ac.titech.c.se.stein.core.Context;
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
        if (analyzer.languageOf(entry.getName()) == null) {
            return entry;  // not a language we can parse
        }
        final SourceModel model = analyzer.analyze(entry.getName(), entry.getBlob(), c);
        if (model == null) {
            return entry;
        }
        final List<Fragment> comments = model.getComments();
        if (comments == null || comments.isEmpty()) {
            return entry;
        }
        return entry.update(FormatUtils.stripComments(model.getRoot().getExtentFragment(), comments));
    }
}
