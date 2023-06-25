package jp.ac.titech.c.se.stein.app;

import java.nio.charset.StandardCharsets;

import jp.ac.titech.c.se.stein.core.SourceText;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.IScanner;
import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jgit.lib.ObjectId;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.ColdEntry.HashEntry;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Slf4j
@Command(name = "java-tokenize", description = "Encode/decode Java source files to/from linetoken format")
public class JavaTokenize extends RepositoryRewriter {
    @Option(names = "--decode", description = "decode tokenlines")
    protected boolean isDecoding = false;

    /**
     * Encodes the given source.
     */
    public static String encode(final String source) {
        final IScanner scanner = ToolFactory.createScanner(true, true, false, JavaCore.VERSION_18);
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

    /**
     * Decodes the given source.
     */
    public static String decode(final String source) {
        return source.replace("\n", "").replace("\r", "\n");
    }

    @Override
    protected ObjectId rewriteBlob(final ObjectId blobId, final Context c) {
        final HashEntry entry = c.getEntry();
        if (!entry.name.toLowerCase().endsWith(".java")) {
            return super.rewriteBlob(blobId, c);
        }
        final String text = SourceText.of(source.readBlob(blobId)).getContent();
        final String converted = isDecoding ? decode(text) : encode(text);
        final ObjectId newId = target.writeBlob(converted.getBytes(StandardCharsets.UTF_8), c);
        log.debug("Rewrite blob: {} -> {} {}", blobId.name(), newId.name(), c);
        return newId;
    }
}
