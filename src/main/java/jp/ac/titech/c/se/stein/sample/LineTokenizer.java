package jp.ac.titech.c.se.stein.sample;

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

import jp.ac.titech.c.se.stein.CLI;
import jp.ac.titech.c.se.stein.ConcurrentRepositoryRewriter;
import jp.ac.titech.c.se.stein.EntrySet.Entry;

public class LineTokenizer extends ConcurrentRepositoryRewriter {
    private static final Logger log = LoggerFactory.getLogger(LineTokenizer.class);

    /**
     * Encodes the given source.
     */
    public static String encode(final String source) {
        final IScanner scanner = ToolFactory.createScanner(true, true, true, JavaCore.VERSION_10);
        scanner.setSource(source.replaceAll("\r\n|\r|\n", "\n").toCharArray());
        final StringBuilder buffer = new StringBuilder();
        try {
            for (;;) {
                final int type = scanner.getNextToken();
                if (type == ITerminalSymbols.TokenNameEOF) {
                    break;
                }
                final String token = new String(scanner.getRawTokenSource());
                buffer.append(token.replace("\n", "\r\n")).append("\n");
            }
        } catch (final InvalidInputException e) {
            e.printStackTrace();
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
    protected ObjectId rewriteBlob(final ObjectId blobId, final Entry entry) {
        if (entry.name.toLowerCase().endsWith(".java")) {
            log.debug("Process: {} ({})", entry.name, blobId);
            final String source = load(blobId);
            final String converted = encode(source);
            return writeBlob(converted.getBytes());
        } else {
            return blobId;
        }
    }

    protected String load(final ObjectId blobId) {
        final byte[] data = readBlob(blobId);
        final String charset = guessCharset(data);
        if (charset != null) {
            try {
                return new String(data, charset);
            } catch (final UnsupportedEncodingException e) {
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
        new CLI(LineTokenizer.class, args).run();
    }
}
