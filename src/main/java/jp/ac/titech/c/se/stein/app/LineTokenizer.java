package jp.ac.titech.c.se.stein.app;

import java.io.UnsupportedEncodingException;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.IScanner;
import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jgit.lib.ObjectId;
import org.mozilla.universalchardet.UniversalDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.EntrySet.Entry;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "like-tokenizer", description = "Encode/decode Java source files to/from linetoken format")
public class LineTokenizer extends RepositoryRewriter {
    private static final Logger log = LoggerFactory.getLogger(LineTokenizer.class);

    @Option(names = "--decode", description = "decode tokenlines")
    protected boolean isDecoding = false;

    /**
     * Encodes the given source.
     */
    public static String encode(final String source) {
        final IScanner scanner = ToolFactory.createScanner(true, true, false, JavaCore.VERSION_18);
        scanner.setSource(source.replaceAll("\r\n|\r|\n", "\n").toCharArray());
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
        final Entry entry = c.getEntry();
        if (!entry.name.toLowerCase().endsWith(".java")) {
            return super.rewriteBlob(blobId, c);
        }
        final String source = load(blobId, c);
        final String converted = isDecoding ? decode(source) : encode(source);
        final ObjectId newId = target.writeBlob(converted.getBytes(), c);
        log.debug("Rewrite blob: {} -> {} {}", blobId.name(), newId.name(), c);
        return newId;
    }

    protected String load(final ObjectId blobId, final Context c) {
        final byte[] data = source.readBlob(blobId, c);
        final String charset = guessCharset(data);
        if (charset != null) {
            try {
                return new String(data, charset);
            } catch (final UnsupportedEncodingException e) {
                log.error(e.getMessage(), e);
            }
        }
        return new String(data);
    }

    protected String guessCharset(final byte[] data) {
        final UniversalDetector detector = new UniversalDetector(null);
        detector.handleData(data, 0, data.length);
        detector.dataEnd();
        return detector.getDetectedCharset();
    }

    public static void main(final String[] args) {
        Application.execute(new LineTokenizer(), args);
    }
}
