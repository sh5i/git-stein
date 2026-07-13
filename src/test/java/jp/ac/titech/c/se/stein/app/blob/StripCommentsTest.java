package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class StripCommentsTest {
    private final Context c = Context.init();
    private final StripComments app = new StripComments();

    private String strip(final String name, final String source) {
        final BlobEntry entry = HotEntry.ofBlob(name, source);
        return new String(((BlobEntry) app.rewriteBlobEntry(entry, c).stream().findFirst().orElseThrow()).getBlob());
    }

    @Test
    public void testRemovesEveryCommentKindAndNormalizesLines() {
        // a whole-line comment takes its line; an inline trailing comment leaves the code and newline;
        // a javadoc/block alone on its lines is removed entirely; a comment inside a body is removed too
        assertEquals("""
                class A {
                    int x = 1;
                    void f() {
                        run();
                    }
                }
                """, strip("A.java", """
                // header line
                class A {
                    /** doc */
                    int x = 1; // trailing
                    void f() {
                        // inside the body
                        run();
                    }
                }
                """));
    }

    @Test
    public void testCommentTokensInStringsAreNotComments() {
        // the parse tree distinguishes a // inside a string literal from a real comment
        assertEquals("""
                String s = "http://example.com";
                """, strip("A.java", """
                String s = "http://example.com"; // a real comment
                """));
    }

    @Test
    public void testInlineBlockCommentBetweenCode() {
        // a block comment sharing a line with code on both sides is removed in place
        assertEquals("int x = 5;\n", strip("A.java", "int x = /* five */ 5;\n"));
    }

    @Test
    public void testMultiLanguage() {
        // Python: a full-line # comment and an inline one; the hash-bang-like line goes too
        assertEquals("""
                def f():
                    return 1
                """, strip("s.py", """
                # module comment
                def f():
                    # body comment
                    return 1  # trailing
                """));
    }

    @Test
    public void testNonSourceFilePassedThrough() {
        final BlobEntry entry = HotEntry.ofBlob("README.md", "# Title\n<!-- note -->\n");
        assertSame(entry, app.rewriteBlobEntry(entry, c).stream().findFirst().orElseThrow());
    }

    @Test
    public void testFileWithNoCommentsPassedThrough() {
        final BlobEntry entry = HotEntry.ofBlob("A.java", "class A {}\n");
        assertSame(entry, app.rewriteBlobEntry(entry, c).stream().findFirst().orElseThrow());
    }
}
