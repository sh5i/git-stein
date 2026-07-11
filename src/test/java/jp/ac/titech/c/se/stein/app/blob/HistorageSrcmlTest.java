package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.util.ProcessRunner;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class HistorageSrcmlTest {
    private final Context c = Context.init();

    @Test
    public void testAttachesComments() {
        assumeTrue(ProcessRunner.isAvailable("srcml"), "srcml not available");
        final Historage h = new Historage().backends(Historage.BackendType.srcml);
        h.requiresComments = true;
        final AnyHotEntry result = h.rewriteBlobEntry(HotEntry.ofBlob("C.java", """
                class C {
                    /** doc for m */
                    void m() {}
                }
                """), c);
        final Map<String, String> entries = result.stream()
                .collect(Collectors.toMap(HotEntry::getName, e -> new String(((BlobEntry) e).getBlob())));
        // the leading comment attaches to m: its (de-indented) text goes to m's comment side file, and m's
        // module keeps only the declaration
        assertEquals("/** doc for m */\n", entries.get("C#m().mjava.com"));
        assertEquals("    void m() {}\n", entries.get("C#m().mjava"));
    }

    @Test
    public void testDocCommentBindsAcrossBlankLine() {
        assumeTrue(ProcessRunner.isAvailable("srcml"), "srcml not available");
        final Historage h = new Historage().backends(Historage.BackendType.srcml);
        h.requiresComments = true;
        final AnyHotEntry result = h.rewriteBlobEntry(HotEntry.ofBlob("C.java", """
                class C {
                    /** doc */

                    void m() {}
                }
                """), c);
        final Map<String, String> entries = result.stream()
                .collect(Collectors.toMap(HotEntry::getName, e -> new String(((BlobEntry) e).getBlob())));
        // a doc comment binds to the declaration below it even across a blank line, via the elaborate rule
        // now shared with the tree-sitter backend
        assertEquals("/** doc */\n", entries.get("C#m().mjava.com"));
    }
}
