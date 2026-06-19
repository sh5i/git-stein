package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;
import picocli.CommandLine.Command;

/**
 * Reformats {@code *.java} blobs across the history with the bundled JDT code formatter (Eclipse
 * built-in profile), with no external dependency. Applying it to the whole history is the radical
 * counterpart to {@code blame.ignoreRevsFile}: formatting-only diffs disappear from blame. Blobs
 * that fail to format (e.g. with syntax errors) and non-Java blobs are left untouched.
 *
 * Inspired by preform's {@code Formatter}
 * (<a href="https://github.com/xecua/preform/blob/main/src/main/kotlin/page/caffeine/preform/filter/normalizer/Formatter.kt">source</a>).
 */
@Slf4j
@ToString
@Command(name = "@pretty", description = "Reformat *.java blobs with the JDT code formatter")
public class Pretty implements BlobTranslator {
    private static final String LINE_SEPARATOR = "\n";

    private final ThreadLocal<CodeFormatter> formatter = ThreadLocal.withInitial(
            () -> ToolFactory.createCodeFormatter(DefaultCodeFormatterConstants.getEclipseDefaultSettings()));

    @Override
    public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
        if (!entry.getName().endsWith(".java")) {
            return entry;
        }
        final String source = entry.getContent();
        final String formatted = format(source);
        return formatted == null || formatted.equals(source) ? entry : entry.update(formatted);
    }

    private String format(final String source) {
        final TextEdit edit = formatter.get().format(
                CodeFormatter.K_COMPILATION_UNIT | CodeFormatter.F_INCLUDE_COMMENTS,
                source, 0, source.length(), 0, LINE_SEPARATOR);
        if (edit == null) {
            return null;
        }
        final IDocument document = new Document(source);
        try {
            edit.apply(document);
        } catch (final BadLocationException e) {
            log.warn("format failed: {}", e.getMessage());
            return null;
        }
        return document.get();
    }
}
