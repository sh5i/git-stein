package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.CommitHeaders;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import lombok.ToString;
import picocli.CommandLine.Command;

/**
 * Removes cryptographic signatures from the history: a commit's {@code gpgsig} and
 * {@code mergetag} headers and a tag's trailing signature block (OpenPGP, X.509, or SSH). Every
 * other byte is preserved, so the result is byte-identical to the input except for the removed
 * signatures.
 */
@ToString
@Command(name = "@strip-sign", description = "Remove GPG signatures and merge tags from commits and tags")
public class StripSign extends RepositoryRewriter {
    /**
     * The signature armor markers git recognizes (see
     * <a href="https://github.com/git/git/blob/master/gpg-interface.c">gpg-interface.c</a>). A tag's
     * signature is the trailing block introduced by one of these at the start of a line.
     */
    private static final String[] SIGNATURE_MARKERS = {
            "-----BEGIN PGP SIGNATURE-----",   // OpenPGP
            "-----BEGIN PGP MESSAGE-----",     // OpenPGP
            "-----BEGIN SIGNED MESSAGE-----",  // X.509
            "-----BEGIN SSH SIGNATURE-----",   // SSH
    };

    @Override
    protected void setUp(final Context c) {
        // A commit signature is an extra header and a tag signature trails the message; both are
        // only reachable in extra-attributes mode, which also keeps every untouched field verbatim.
        config.isRewritingExtraAttributes = true;
    }

    @Override
    protected CommitHeaders rewriteExtraHeaders(final CommitHeaders headers, final Context c) {
        headers.remove("gpgsig");   // the commit signature (OpenPGP, X.509, or SSH)
        headers.remove("mergetag"); // a merged annotated tag embedded in the commit, signature and all
        return headers;
    }

    @Override
    protected String rewriteTagMessage(final String message, final Context c) {
        return message.substring(0, parseSignedBuffer(message));
    }

    /**
     * Returns the length of the payload that precedes a trailing signature — equivalently, the
     * offset at which the signature begins — or the whole length when the message is unsigned.
     * Follows git's
     * <a href="https://github.com/git/git/blob/master/gpg-interface.c">parse_signed_buffer</a>.
     */
    private static int parseSignedBuffer(final String message) {
        // The payload ends at the last line that begins with an armor marker; git never scans for
        // the closing marker, and requiring the marker at a line start ignores one quoted mid-message.
        int match = message.length();
        for (int lineStart = 0; lineStart < message.length(); ) {
            if (isMarkerLine(message, lineStart)) {
                match = lineStart;
            }
            final int nl = message.indexOf('\n', lineStart);
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
        }
        return match;
    }

    /**
     * Tests whether one of the {@link #SIGNATURE_MARKERS} begins at offset {@code pos}.
     */
    private static boolean isMarkerLine(final String message, final int pos) {
        for (final String marker : SIGNATURE_MARKERS) {
            if (message.startsWith(marker, pos)) {
                return true;
            }
        }
        return false;
    }
}
