package jp.ac.titech.c.se.stein.entry;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class EntryTest {
    static final ObjectId SAMPLE_ID = ObjectId.fromString("abcdef0123456789abcdef0123456789abcdef01");
    static final ObjectId OTHER_ID = ObjectId.fromString("1234567890abcdef1234567890abcdef12345678");
    static final int BLOB_MODE = FileMode.REGULAR_FILE.getBits();
    static final int TREE_MODE = FileMode.TREE.getBits();
    static final int LINK_MODE = FileMode.GITLINK.getBits();

    final Entry blob = Entry.of(BLOB_MODE, "hello", SAMPLE_ID);
    final Entry tree = Entry.of(TREE_MODE, "hello", SAMPLE_ID);
    final Entry link = Entry.of(LINK_MODE, "hello", SAMPLE_ID);

    @Test
    public void testFactories() {
        assertEquals(BLOB_MODE, blob.getMode());
        assertEquals("hello", blob.getName());
        assertEquals(SAMPLE_ID, blob.getId());
        assertNull(blob.getDirectory());

        final Entry e2 = Entry.of(BLOB_MODE, "hello", SAMPLE_ID, "src");
        assertEquals("src", e2.getDirectory());
    }

    @Test
    public void testType() {
        assertTrue(blob.isBlob());
        assertFalse(blob.isTree());
        assertFalse(blob.isLink());
        assertEquals(SingleEntry.Type.blob, blob.getType());

        assertTrue(tree.isTree());
        assertFalse(tree.isBlob());
        assertFalse(tree.isLink());
        assertEquals(SingleEntry.Type.tree, tree.getType());

        assertTrue(link.isLink());
        assertFalse(link.isBlob());
        assertFalse(link.isTree());
        assertEquals(SingleEntry.Type.link, link.getType());
    }

    @Test
    public void testGetPath() {
        assertEquals("hello", blob.getPath());
        assertEquals("src/main/hello", Entry.of(BLOB_MODE, "hello", SAMPLE_ID, "src/main").getPath());
    }

    @Test
    public void testIsRoot() {
        assertTrue(Entry.of(TREE_MODE, "", SAMPLE_ID).isRoot());
        assertFalse(tree.isRoot());
        assertFalse(Entry.of(BLOB_MODE, "", SAMPLE_ID).isRoot());
    }

    @Test
    public void testCompareTo() {
        assertEquals("hello", blob.sortKey());
        assertEquals("hello/", tree.sortKey());
        assertTrue(blob.compareTo(tree) < 0);

        // blob "hello" < blob "hello.txt" but tree "hello" > blob "hello.txt" since '/' (0x2F) > '.' (0x2E)
        final Entry blob2 = Entry.of(BLOB_MODE, "hello.txt", SAMPLE_ID);
        assertTrue(blob.compareTo(blob2) < 0);
        assertTrue(tree.compareTo(blob2) > 0);
    }

    @Test
    public void testStream() {
        assertEquals(1, blob.size());
        final List<Entry> list = blob.stream().collect(Collectors.toList());
        assertEquals(1, list.size());
        assertSame(blob, list.get(0));
    }

    @Test
    public void testEquals() {
        final Entry same = Entry.of(BLOB_MODE, "hello", SAMPLE_ID);
        assertEquals(blob, same);
        assertEquals(blob.hashCode(), same.hashCode());
        assertNotEquals(blob, Entry.of(BLOB_MODE, "hello", OTHER_ID));
        assertNotEquals(blob, Entry.of(BLOB_MODE, "other", SAMPLE_ID));
    }

    @Test
    public void testToString() {
        assertEquals("hello [abcdef0123456789abcdef0123456789abcdef01:100644]", blob.toString());
        assertEquals("hello [abcdef0123456789abcdef0123456789abcdef01:40000]", tree.toString());
        assertEquals("hello [abcdef0123456789abcdef0123456789abcdef01:160000]", link.toString());

        final Entry withDir = Entry.of(BLOB_MODE, "hello", SAMPLE_ID, "src");
        assertEquals("src/hello [abcdef0123456789abcdef0123456789abcdef01:100644]", withDir.toString());
    }
}
