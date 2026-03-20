package jp.ac.titech.c.se.stein.core;

import jp.ac.titech.c.se.stein.entry.Entry;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class RepositoryAccessTest {
    static final byte[] HELLO = "hello".getBytes(StandardCharsets.UTF_8);
    static final byte[] WORLD = "world".getBytes(StandardCharsets.UTF_8);
    static final int BLOB_MODE = FileMode.REGULAR_FILE.getBits();
    static final PersonIdent IDENT = new PersonIdent("Test", "test@example.com");

    Repository repo;
    RepositoryAccess ra;
    ObjectInserter inserter;
    Context c;

    @BeforeEach
    void setUp() {
        repo = new InMemoryRepository(new DfsRepositoryDescription("test"));
        ra = new RepositoryAccess(repo);
        inserter = repo.newObjectInserter();
        c = Context.init().with(Context.Key.inserter, inserter);
    }

    @AfterEach
    void tearDown() {
        inserter.close();
        repo.close();
    }

    void flush() {
        Try.io(() -> inserter.flush());
    }

    // --- Blob ---

    @Test
    public void testBlob() {
        final ObjectId blobId = ra.writeBlob(HELLO, c);
        flush();

        assertArrayEquals(HELLO, ra.readBlob(blobId));
        assertEquals(HELLO.length, ra.getBlobSize(blobId));
    }

    // --- Tree ---

    @Test
    public void testTree() {
        final ObjectId blob1 = ra.writeBlob(HELLO, c);
        final ObjectId blob2 = ra.writeBlob(WORLD, c);
        Entry[] entries1 = new Entry[]{Entry.of(BLOB_MODE, "hello.txt", blob1), Entry.of(BLOB_MODE, "world.txt", blob2)};
        final ObjectId treeId = ra.writeTree(List.of(entries1), c);
        flush();

        final List<Entry> entries = ra.readTree(treeId, null);
        assertEquals(2, entries.size());
        assertEquals("hello.txt", entries.get(0).getName());
        assertEquals("world.txt", entries.get(1).getName());
        assertNull(entries.get(0).getDirectory());
    }

    @Test
    public void testTreeWithPath() {
        Entry[] entries1 = new Entry[]{Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c))};
        final ObjectId treeId = ra.writeTree(List.of(entries1), c);
        flush();

        final List<Entry> entries = ra.readTree(treeId, "src");
        assertEquals("src", entries.get(0).getDirectory());
    }

    @Test
    public void testNestedTree() {
        Entry[] entries1 = new Entry[]{Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c))};
        final ObjectId inner = ra.writeTree(List.of(entries1), c);
        Entry[] entries = new Entry[]{Entry.of(FileMode.TREE.getBits(), "subdir", inner)};
        final ObjectId outer = ra.writeTree(List.of(entries), c);
        flush();

        final List<Entry> outerEntries = ra.readTree(outer, null);
        assertEquals(1, outerEntries.size());
        assertTrue(outerEntries.get(0).isTree());

        final List<Entry> innerEntries = ra.readTree(outerEntries.get(0).getId(), "subdir");
        assertEquals("hello.txt", innerEntries.get(0).getName());
    }

    @Test
    public void testEmptyTree() {
        final ObjectId treeId = ra.writeTree(List.of(), c);
        flush();
        assertTrue(ra.readTree(treeId, null).isEmpty());
    }

    // --- Commit ---

    @Test
    public void testCommit() {
        Entry[] entries = new Entry[]{Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c))};
        final ObjectId treeId = ra.writeTree(List.of(entries), c);
        final ObjectId commit1 = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeId, IDENT, IDENT, "first", c);
        flush();

        assertNotEquals(ObjectId.zeroId(), commit1);
        assertEquals(Constants.OBJ_COMMIT, ra.getObjectType(commit1));

        final ObjectId commit2 = ra.writeCommit(new ObjectId[]{commit1}, treeId, IDENT, IDENT, "second", c);
        flush();
        assertNotEquals(commit1, commit2);
    }

    // --- Copy ---

    @Test
    public void testCopyBlob() {
        try (final InMemoryRepository targetRepo = new InMemoryRepository(new DfsRepositoryDescription("target"))) {
            final RepositoryAccess targetRa = new RepositoryAccess(targetRepo);
            try (final ObjectInserter targetInserter = targetRepo.newObjectInserter()) {
                final Context tc = Context.init().with(Context.Key.inserter, targetInserter);

                final ObjectId blobId = ra.writeBlob(HELLO, c);
                flush();

                final ObjectId copiedId = ra.copyBlob(blobId, targetRa, tc);
                Try.io(() -> targetInserter.flush());
                assertArrayEquals(HELLO, targetRa.readBlob(copiedId));
            }
        }
    }

    @Test
    public void testCopyTree() {
        try (final InMemoryRepository targetRepo = new InMemoryRepository(new DfsRepositoryDescription("target"))) {
            final RepositoryAccess targetRa = new RepositoryAccess(targetRepo);
            try (final ObjectInserter targetInserter = targetRepo.newObjectInserter()) {
                final Context tc = Context.init().with(Context.Key.inserter, targetInserter);

                Entry[] entries = new Entry[]{Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c))};
                final ObjectId treeId = ra.writeTree(List.of(entries), c);
                flush();

                final ObjectId copiedTreeId = ra.copyTree(treeId, targetRa, tc);
                Try.io(() -> targetInserter.flush());
                assertEquals("hello.txt", targetRa.readTree(copiedTreeId, null).get(0).getName());
            }
        }
    }

    // --- Ref ---

    @Test
    public void testRef() {
        Entry[] entries = new Entry[]{Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c))};
        ObjectId treeId = ra.writeTree(List.of(entries), c);
        final ObjectId commitId = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeId, IDENT, IDENT, "hello", c);
        flush();

        ra.applyRefUpdate(new RefEntry("refs/heads/main", commitId));
        ra.applyRefUpdate(new RefEntry("refs/heads/dev", commitId));

        final Ref ref = ra.getRef("refs/heads/main");
        assertNotNull(ref);
        assertEquals(commitId, ref.getObjectId());

        assertTrue(ra.getRefs().size() >= 2);
    }

    @Test
    public void testRefDelete() {
        Entry[] entries = new Entry[]{Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c))};
        ObjectId treeId = ra.writeTree(List.of(entries), c);
        final ObjectId commitId = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeId, IDENT, IDENT, "hello", c);
        flush();

        ra.applyRefUpdate(new RefEntry("refs/heads/temp", commitId));
        assertNotNull(ra.getRef("refs/heads/temp"));

        ra.applyRefDelete(new RefEntry("refs/heads/temp", commitId));
        assertNull(ra.getRef("refs/heads/temp"));
    }

    // --- Notes ---

    @Test
    public void testNotes() {
        Entry[] entries = new Entry[]{Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c))};
        ObjectId treeId = ra.writeTree(List.of(entries), c);
        final ObjectId commitId = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeId, IDENT, IDENT, "hello", c);
        flush();

        final NoteMap notes = ra.getDefaultNotes();
        ra.addNote(notes, commitId, "note body".getBytes(StandardCharsets.UTF_8), c);
        flush();

        assertNotNull(Try.io(() -> notes.get(commitId)));
    }

    // --- Dry run ---

    @Test
    public void testDryRun() {
        ra.setDryRunning(true);
        final ObjectId blobId = ra.writeBlob(HELLO, c);
        assertNotNull(blobId);
    }

    // --- Static ---

    @Test
    public void testResolveNameConflicts() {
        final ObjectId id1 = ObjectId.fromString("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        final ObjectId id2 = ObjectId.fromString("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
        final ObjectId id3 = ObjectId.fromString("cccccccccccccccccccccccccccccccccccccccc");
        final List<Entry> entries = List.of(
                Entry.of(BLOB_MODE, "hello", id1),
                Entry.of(BLOB_MODE, "world", id2),
                Entry.of(BLOB_MODE, "hello", id3));
        final List<Entry> result = RepositoryAccess.resolveNameConflicts(entries);
        assertEquals("hello", result.get(0).name);
        assertEquals(id1, result.get(0).id);
        assertEquals("world", result.get(1).name);
        assertEquals(id2, result.get(1).id);
        assertEquals("hello@2", result.get(2).name);
        assertEquals(id3, result.get(2).id);
    }

    @Test
    public void testWalk() {
        assertNotNull(ra.walk());
    }

    @Test
    public void testCollectCommits() {
        Entry[] entries = new Entry[]{Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c))};
        ObjectId treeId = ra.writeTree(List.of(entries), c);
        final ObjectId commit1 = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeId, IDENT, IDENT, "first", c);
        final ObjectId commit2 = ra.writeCommit(new ObjectId[]{commit1}, treeId, IDENT, IDENT, "second", c);
        flush();

        ra.applyRefUpdate(new RefEntry("refs/heads/main", commit2));

        final List<RevCommit> commits = ra.collectCommits("refs/heads/main");
        assertEquals(2, commits.size());
        assertEquals(commit1, commits.get(0).getId());
        assertEquals(commit2, commits.get(1).getId());
        assertEquals("first", commits.get(0).getFullMessage());
        assertEquals("second", commits.get(1).getFullMessage());

        // non-existent ref returns empty
        assertTrue(ra.collectCommits("refs/heads/nonexistent").isEmpty());
    }
}
