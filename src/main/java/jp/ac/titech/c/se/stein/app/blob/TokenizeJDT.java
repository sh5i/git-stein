package jp.ac.titech.c.se.stein.app.blob;

import java.nio.charset.StandardCharsets;

import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.entry.HotEntry;
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

@Slf4j
@ToString
@Command(name = "@tokenize-jdt", description = "Encode Java source files to linetoken format via JDT")
public class TokenizeJDT implements BlobTranslator {
    /**
     * Encodes the given source to linetoken format.
     */
    public static String encode(final String source) {
        final IScanner scanner = ToolFactory.createScanner(true, true, false, JavaCore.VERSION_17);
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

    @Override
    public AnyHotEntry rewriteBlobEntry(final HotEntry entry, final Context c) {
        if (!entry.getName().toLowerCase().endsWith(".java")) {
            return entry;
        }
        final String text = SourceText.of(entry.getBlob()).getContent();
        final byte[] newBlob = encode(text).getBytes(StandardCharsets.UTF_8);
        return entry.update(newBlob);
    }
}
