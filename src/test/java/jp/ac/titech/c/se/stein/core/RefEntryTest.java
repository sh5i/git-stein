package jp.ac.titech.c.se.stein.core;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RefEntryTest {
    static final ObjectId SAMPLE_ID = ObjectId.fromString("abcdef0123456789abcdef0123456789abcdef01");
    final RefEntry direct = new RefEntry("refs/heads/main", SAMPLE_ID);
    final RefEntry symbolic = new RefEntry("HEAD", "refs/heads/main");

    @Test
    public void testDirectRef() {
        assertEquals("refs/heads/main", direct.name);
        assertEquals(SAMPLE_ID, direct.id);
        assertNull(direct.target);
        assertFalse(direct.isSymbolic());
    }

    @Test
    public void testSymbolicRef() {
        assertEquals("HEAD", symbolic.name);
        assertNull(symbolic.id);
        assertEquals("refs/heads/main", symbolic.target);
        assertTrue(symbolic.isSymbolic());
    }

    @Test
    public void testEmpty() {
        assertNull(RefEntry.EMPTY.name);
        assertNull(RefEntry.EMPTY.id);
        assertNull(RefEntry.EMPTY.target);
    }

    @Test
    public void testEquals() {
        final RefEntry same = new RefEntry("refs/heads/main", SAMPLE_ID);
        assertEquals(direct, same);
        assertEquals(direct.hashCode(), same.hashCode());

        assertNotEquals(direct, new RefEntry("refs/heads/dev", SAMPLE_ID));
        assertNotEquals(direct, symbolic);
    }

    @Test
    public void testToString() {
        assertEquals("<Ref:refs/heads/main abcdef0123456789abcdef0123456789abcdef01>", direct.toString());
        assertEquals("<Ref:HEAD refs/heads/main>", symbolic.toString());
    }
}
