package jp.ac.titech.c.se.stein.entry;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class AnyColdEntryTest {
    static final ObjectId ID1 = ObjectId.fromString("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    static final ObjectId ID2 = ObjectId.fromString("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    static final int BLOB_MODE = FileMode.REGULAR_FILE.getBits();

    final Entry e1 = Entry.of(BLOB_MODE, "hello.txt", ID1);
    final Entry e2 = Entry.of(BLOB_MODE, "world.txt", ID2);

    @Test
    public void testEmpty() {
        final AnyColdEntry.Empty empty = AnyColdEntry.empty();
        assertEquals(0, empty.size());
        assertEquals(0, empty.stream().count());
        assertSame(empty, empty.pack());
        assertEquals("[]", empty.toString());

        final AnyColdEntry.Empty empty2 = AnyColdEntry.empty();
        assertEquals(empty, empty2);
    }

    @Test
    public void testSet() {
        final AnyColdEntry.Set fromEmpty = AnyColdEntry.set();
        assertEquals(0, fromEmpty.size());

        final AnyColdEntry.Set fromVarargs = AnyColdEntry.set(e1, e2);
        assertEquals(2, fromVarargs.size());

        final AnyColdEntry.Set fromAdds = AnyColdEntry.set();
        fromAdds.add(e1);
        fromAdds.add(e2);
        assertEquals(2, fromAdds.size());

        final List<Entry> entries = fromAdds.stream().collect(Collectors.toList());
        assertEquals(List.of(e1, e2), entries);

        assertEquals(fromVarargs, fromAdds);
    }

    @Test
    public void testPack() {
        // empty set packs to Empty
        assertInstanceOf(AnyColdEntry.Empty.class, AnyColdEntry.set().pack());

        // singleton set packs to the sole Entry
        assertSame(e1, AnyColdEntry.set(e1).pack());

        // multiple set packs to itself
        final AnyColdEntry.Set multiple = AnyColdEntry.set(e1, e2);
        assertSame(multiple, multiple.pack());

        // Entry packs to itself
        assertSame(e1, e1.pack());
    }

    @Test
    public void testSetEquals() {
        final AnyColdEntry.Set s1 = AnyColdEntry.set(e1, e2);
        final AnyColdEntry.Set s2 = AnyColdEntry.set(e1, e2);
        assertEquals(s1, s2);
        assertEquals(s1.hashCode(), s2.hashCode());

        assertNotEquals(s1, AnyColdEntry.set(e1));
    }

    @Test
    public void testToString() {
        assertEquals("[hello.txt [aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:100644]]",
                AnyColdEntry.set(e1).toString());

        assertEquals("[hello.txt [aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa:100644], world.txt [bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb:100644]]",
                AnyColdEntry.set(e1, e2).toString());

        assertEquals("[]", AnyColdEntry.set().toString());
    }
}
