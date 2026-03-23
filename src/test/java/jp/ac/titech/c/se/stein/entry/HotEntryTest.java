package jp.ac.titech.c.se.stein.entry;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class HotEntryTest {
    // TODO: tests for SourceBlob

    static final int BLOB_MODE = FileMode.REGULAR_FILE.getBits();
    static final byte[] HELLO = "hello".getBytes(StandardCharsets.UTF_8);

    final BlobEntry nb = HotEntry.of(BLOB_MODE, "hello", HELLO);

    @Test
    public void testFactories() {
        assertEquals(BLOB_MODE, nb.getMode());
        assertEquals("hello", nb.getName());
        assertArrayEquals(HELLO, nb.getBlob());
        assertNull(nb.getDirectory());

        final BlobEntry withDir = HotEntry.of(BLOB_MODE, "hello", HELLO, "src");
        assertEquals("src", withDir.getDirectory());

        final Entry entry = Entry.of(BLOB_MODE, "hello", ObjectId.zeroId());
        final BlobEntry fromEntry = HotEntry.of(entry, "world".getBytes(StandardCharsets.UTF_8));
        assertEquals("hello", fromEntry.getName());
    }

    @Test
    public void testBlobSize() {
        assertEquals(5, nb.getBlobSize());
    }

    @Test
    public void testSingleEntryMethods() {
        final BlobEntry withDir = HotEntry.of(BLOB_MODE, "hello", HELLO, "src");
        assertEquals("src/hello", withDir.getPath());
        assertTrue(withDir.isBlob());
        assertEquals(SingleEntry.Type.blob, withDir.getType());
    }

    @Test
    public void testStream() {
        assertEquals(1, nb.size());
        assertSame(nb, nb.stream().findFirst().orElseThrow());
    }

    @Test
    public void testRename() {
        final BlobEntry withDir = HotEntry.of(BLOB_MODE, "hello", HELLO, "dir");
        final BlobEntry renamed = withDir.rename("world");
        assertNotSame(withDir, renamed);
        assertEquals("world", renamed.getName());
        assertEquals("hello", withDir.getName());
        assertArrayEquals(HELLO, renamed.getBlob());
        assertEquals("dir", renamed.getDirectory());
    }

    @Test
    public void testUpdate() {
        final byte[] newData = "world".getBytes(StandardCharsets.UTF_8);

        final BlobEntry updated = nb.update(newData);
        assertNotSame(nb, updated);
        assertArrayEquals(newData, updated.getBlob());
        assertEquals("hello", updated.getName());

        assertArrayEquals(newData, nb.update("world").getBlob());
    }

    @Test
    public void testToString() {
        assertEquals("src/hello [new(5):100644]", HotEntry.of(BLOB_MODE, "hello", HELLO, "src").toString());
    }
}
