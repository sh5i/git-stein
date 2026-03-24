package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.IScanner;
import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.compiler.InvalidInputException;

import jp.ac.titech.c.se.stein.core.Context;
import picocli.CommandLine.Command;

/**
 * Encodes Java source files to linetoken format using the JDT scanner.
 * Unlike {@link Tokenize}, this tokenizer is Java-aware and preserves comments
 * and string literals as single tokens. Only targets {@code *.java} files.
 */
@Slf4j
@ToString
@Command(name = "@tokenize-jdt", description = "Encode Java source files to linetoken format via JDT")
public class TokenizeViaJDT implements BlobTranslator {
    @Override
    public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
        if (!HistorageViaJDT.JAVA.accept(entry)) {
            return entry;
        }
        final String text = SourceText.of(entry.getBlob()).getContent();
        return entry.update(encode(text));
    }

    /**
     * Encodes the given source to linetoken format.
     */
    public static String encode(final String source) {
        final IScanner scanner = ToolFactory.createScanner(true, true, false, JavaCore.VERSION_25);
        scanner.setSource(source.toCharArray());
        final StringBuilder buffer = new StringBuilder();
        try {
            for (;;) {
                final int type = scanner.getNextToken();
                if (type == ITerminalSymbols.TokenNameEOF) {
                    break;
                }
                final String token = new String(scanner.getCurrentTokenSource());
                buffer.append(token.replace("\n", "\r")).append("\n");
            }
        } catch (final InvalidInputException e) {
            log.error(e.getMessage(), e);
        }
        return buffer.toString();
    }
}
