package jp.ac.titech.c.se.stein.entry;

import org.eclipse.jgit.lib.FileMode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class AnyHotEntryTest {
    static final int BLOB_MODE = FileMode.REGULAR_FILE.getBits();
    static final byte[] HELLO = "hello".getBytes(StandardCharsets.UTF_8);
    static final byte[] WORLD = "world".getBytes(StandardCharsets.UTF_8);

    final BlobEntry h1 = HotEntry.of(BLOB_MODE, "hello.txt", HELLO);
    final BlobEntry h2 = HotEntry.of(BLOB_MODE, "world.txt", WORLD);

    @Test
    public void testEmpty() {
        final AnyHotEntry.Empty empty = AnyHotEntry.empty();
        assertEquals(0, empty.size());
        assertEquals(0, empty.stream().count());
    }

    @Test
    public void testFold() {
        // empty
        final AnyColdEntry folded = AnyHotEntry.empty().fold(null, null);
        assertInstanceOf(AnyColdEntry.Empty.class, folded);

        // TODO: meaningful tests for fold
    }

    @Test
    public void testSet() {
        final AnyHotEntry.Set fromEmpty = AnyHotEntry.set();
        assertEquals(0, fromEmpty.size());

        final AnyHotEntry.Set fromVarargs = AnyHotEntry.set(h1, h2);
        assertEquals(2, fromVarargs.size());

        final AnyHotEntry.Set fromCollection = AnyHotEntry.set(Arrays.asList(h1, h2));
        assertEquals(2, fromCollection.size());

        final AnyHotEntry.Set fromAdds = AnyHotEntry.set();
        fromAdds.add(h1);
        fromAdds.add(h2);
        assertEquals(2, fromAdds.size());

        final List<HotEntry> entries = fromAdds.stream().collect(Collectors.toList());
        assertEquals(Arrays.asList(h1, h2), entries);
    }

    @Test
    public void testSingleHotEntryAsAnyHotEntry() {
        final AnyHotEntry any = h1;
        assertEquals(1, any.size());
        assertSame(h1, any.stream().findFirst().orElseThrow());
    }

    @Test
    public void testToString() {
        assertEquals("[hello.txt [new(5):100644]]",
                AnyHotEntry.set(h1).toString());

        assertEquals("[hello.txt [new(5):100644], world.txt [new(5):100644]]",
                AnyHotEntry.set(h1, h2).toString());

        assertEquals("[]", AnyHotEntry.set().toString());
    }
}
