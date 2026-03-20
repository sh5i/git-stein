package jp.ac.titech.c.se.stein.core;

import jp.ac.titech.c.se.stein.entry.Entry;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class ContextTest {
    static final ObjectId SAMPLE_ID = ObjectId.fromString("abcdef0123456789abcdef0123456789abcdef01");
    static final Entry SAMPLE_ENTRY = Entry.of(FileMode.REGULAR_FILE.getBits(), "hello", SAMPLE_ID);

    final Context empty = Context.init();
    final Context withPath = empty.with(Context.Key.path, "src");

    @Test
    public void testInit() {
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.size());
        assertNull(empty.getPath());
    }

    @Test
    public void testWith() {
        // returns new instance
        assertNotSame(empty, withPath);

        // does not mutate original
        assertNull(empty.get(Context.Key.path));

        // sets value
        assertEquals("src", withPath.get(Context.Key.path));
        assertEquals("src", withPath.getPath());

        // two-key variant
        final Context c = empty.with(Context.Key.path, "src", Context.Key.entry, SAMPLE_ENTRY);
        assertEquals("src", c.getPath());
        assertSame(SAMPLE_ENTRY, c.getEntry());

        // chaining
        final Context chained = empty
                .with(Context.Key.path, "src")
                .with(Context.Key.entry, SAMPLE_ENTRY);
        assertEquals("src", chained.getPath());
        assertSame(SAMPLE_ENTRY, chained.getEntry());
    }

    @Test
    public void testMapView() {
        assertTrue(withPath.containsKey(Context.Key.path));
        assertFalse(withPath.containsKey(Context.Key.commit));
        assertTrue(withPath.containsValue("src"));

        assertEquals(1, withPath.keySet().size());
        assertTrue(withPath.keySet().contains(Context.Key.path));

        assertEquals(1, withPath.entrySet().size());
        assertEquals(1, withPath.values().size());
    }

    @Test
    public void testMutationThrows() {
        assertThrows(UnsupportedOperationException.class, () -> empty.put(Context.Key.path, "src"));
        assertThrows(UnsupportedOperationException.class, () -> empty.remove(Context.Key.path));
        assertThrows(UnsupportedOperationException.class, empty::clear);
    }

    @Test
    public void testToString() {
        assertEquals("", empty.toString());
        assertEquals("(path: \"src\")", withPath.toString());
    }
}
