package jp.ac.titech.c.se.stein.util;

import jp.ac.titech.c.se.stein.entry.Entry;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HashUtilsTest {
    static final byte[] HELLO = "hello".getBytes(StandardCharsets.UTF_8);

    // SHA-1("hello")
    static final String HELLO_SHA1 = "aaf4c61ddcc5e8a2dabede0f3b482cd9aea9434d";

    // git hash-object --stdin <<< "hello" (without newline)
    static final String HELLO_BLOB_ID = "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0";
    static final String WORLD_BLOB_ID = "04fea06420ca60892f73becee3614f6d023a4b7f";

    static final int BLOB_MODE = FileMode.REGULAR_FILE.getBits();

    @Test
    public void testDigest() {
        assertEquals(HELLO_SHA1, HashUtils.digest(HELLO));
        assertEquals(HELLO_SHA1, HashUtils.digest("hello"));
        assertNotEquals(HashUtils.digest("hello"), HashUtils.digest("world"));

        assertEquals("aaf4c61", HashUtils.digest(HELLO, 7));
        assertEquals("aaf4c61", HashUtils.digest("hello", 7));
    }

    @Test
    public void testIdForBlob() {
        final ObjectId id = HashUtils.idFor(HELLO);
        assertEquals(HELLO_BLOB_ID, id.name());
        assertNotEquals(HELLO_SHA1, id.name());
    }

    @Test
    public void testIdForTree() {
        final Entry hello = Entry.of(BLOB_MODE, "hello.txt", ObjectId.fromString(HELLO_BLOB_ID));
        final Entry world = Entry.of(BLOB_MODE, "world.txt", ObjectId.fromString(WORLD_BLOB_ID));

        // single entry tree
        assertEquals("04df07b08ca746b3167d0f1d1514e2f39a52c16c",
                HashUtils.idFor(List.of(hello)).name());

        // two entry tree
        assertEquals("545ca79da0b5332d6fe3e1566b44d096d7fdb027",
                HashUtils.idFor(List.of(hello, world)).name());

        // order should not matter (sorted internally)
        assertEquals(HashUtils.idFor(List.of(hello, world)), HashUtils.idFor(List.of(world, hello)));
    }
}
