package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PrettyTest {
    private final Context c = Context.init();
    private final Pretty app = new Pretty();

    @Test
    public void testFormatsJava() {
        final AnyHotEntry out = app.rewriteBlobEntry(HotEntry.ofBlob("A.java", "class A{int x;void f(){x=1;}}"), c);
        final String formatted = ((BlobEntry) out).getContent();
        assertTrue(formatted.contains("x = 1"), formatted);      // spaces inserted around '='
        assertTrue(formatted.contains("\n\tvoid f()"), formatted); // indented onto its own line
    }

    @Test
    public void testNonJavaBlobIsUntouched() {
        final BlobEntry in = HotEntry.ofBlob("README.md", "#  hi\n");
        assertSame(in, app.rewriteBlobEntry(in, c));
    }

    @Test
    public void testUnparseableJavaIsUntouched() {
        final BlobEntry in = HotEntry.ofBlob("Broken.java", "this is not valid java");
        assertSame(in, app.rewriteBlobEntry(in, c));
    }
}
