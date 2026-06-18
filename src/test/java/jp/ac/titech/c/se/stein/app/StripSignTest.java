package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.CommitHeaders;
import jp.ac.titech.c.se.stein.core.Context;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StripSignTest {
    private final StripSign app = new StripSign();
    private final Context c = Context.init();

    @Test
    public void testRemovesCommitSignatureHeaders() {
        final CommitHeaders headers = new CommitHeaders(
                "gpgsig fakesig\nmergetag faketag\nchange-id Iabc\n".getBytes(StandardCharsets.UTF_8));
        app.rewriteExtraHeaders(headers, c);
        assertFalse(headers.contains("gpgsig"));
        assertFalse(headers.contains("mergetag"));
        assertTrue(headers.contains("change-id")); // unrelated headers are kept
    }

    @Test
    public void testStripsPgpTagSignature() {
        final String signed = "release v1.0\n-----BEGIN PGP SIGNATURE-----\nABCD\n-----END PGP SIGNATURE-----\n";
        assertEquals("release v1.0\n", app.rewriteTagMessage(signed, c));
    }

    @Test
    public void testStripsSshTagSignature() {
        final String signed = "tagged\n-----BEGIN SSH SIGNATURE-----\nXX\n-----END SSH SIGNATURE-----\n";
        assertEquals("tagged\n", app.rewriteTagMessage(signed, c));
    }

    @Test
    public void testUnsignedTagMessageIsUnchanged() {
        assertEquals("release v1.0\n", app.rewriteTagMessage("release v1.0\n", c));
    }

    @Test
    public void testMarkerQuotedMidLineIsNotStripped() {
        // The marker is not at the start of a line, so (like git) it is not treated as a signature.
        final String prose = "mentions -----BEGIN PGP SIGNATURE----- in the text\n";
        assertEquals(prose, app.rewriteTagMessage(prose, c));
    }

    @Test
    public void testTrailingBlankLinesBeforeSignatureArePreserved() {
        // Cutting at the marker line keeps the message bytes verbatim, including trailing blanks.
        final String signed = "body\n\n-----BEGIN PGP SIGNATURE-----\nX\n-----END PGP SIGNATURE-----\n";
        assertEquals("body\n\n", app.rewriteTagMessage(signed, c));
    }
}
