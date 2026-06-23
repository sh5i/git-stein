package jp.ac.titech.c.se.stein.core;

import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import jp.ac.titech.c.se.stein.util.RawGitObjectCodec;
import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.errors.ObjectWritingException;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
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
        Entry[] entries1 = new Entry[] { Entry.of(BLOB_MODE, "hello.txt", blob1), Entry.of(BLOB_MODE, "world.txt", blob2) };
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
        Entry[] entries1 = new Entry[] { Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c)) };
        final ObjectId treeId = ra.writeTree(List.of(entries1), c);
        flush();

        final List<Entry> entries = ra.readTree(treeId, "src");
        assertEquals("src", entries.get(0).getDirectory());
    }

    @Test
    public void testNestedTree() {
        Entry[] entries1 = new Entry[] { Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c)) };
        final ObjectId inner = ra.writeTree(List.of(entries1), c);
        Entry[] entries = new Entry[] { Entry.of(FileMode.TREE.getBits(), "subdir", inner) };
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

    /**
     * Builds a tree whose serialized size exceeds JGit's default threshold (50MB).
     * Requires an on-disk (file-based) repo because {@link org.eclipse.jgit.storage.file.WindowCacheConfig} only applies there.
     */
    private static ObjectId buildLargeTree(final RepositoryAccess ra) throws IOException {
        try (final ObjectInserter ins = ra.repo.newObjectInserter()) {
            final Context tc = Context.init().with(Context.Key.inserter, ins);
            final ObjectId blobId = ra.writeBlob(HELLO, tc);
            final TreeFormatter f = new TreeFormatter();
            final String suffix = "x".repeat(192);
            // Entry [mode + space + name(200) + null + sha(20)] (~228) * 235,000 > 50MB
            for (int i = 0; i < 235000; i++) {
                f.append(String.format("%08d%s", i, suffix), FileMode.REGULAR_FILE, blobId);
            }
            final ObjectId treeId = f.insertTo(ins);
            ins.flush();
            try (final ObjectReader reader = ra.repo.newObjectReader()) {
                assertTrue(reader.getObjectSize(treeId, Constants.OBJ_TREE) > 50L * 1024 * 1024);
            }
            return treeId;
        }
    }

    @Test
    public void testReadLargeTreeFailsAtDefault() throws Exception {
        // With JGit's default threashold (50MB), a tree larger than 50MB throws LargeObjectException
        RepositoryAccess.setStreamFileThreshold(PackConfig.DEFAULT_BIG_FILE_THRESHOLD);
        try (final RepositoryAccess ra = TestRepo.create(true)) {
            final ObjectId treeId = buildLargeTree(ra);
            assertThrows(LargeObjectException.class, () -> ra.readTree(treeId, null));
        }
    }

    @Test
    public void testReadLargeTreeSucceedsAtMax() throws Exception {
        // With the production setting (Integer.MAX_VALUE), the same tree round-trips successfully
        RepositoryAccess.setStreamFileThreshold(Integer.MAX_VALUE);
        try (final RepositoryAccess ra = TestRepo.create(true)) {
            final ObjectId treeId = buildLargeTree(ra);
            final List<Entry> entries = ra.readTree(treeId, null);
            assertFalse(entries.isEmpty());
        }
    }

    // --- Commit ---

    @Test
    public void testCommit() {
        Entry[] entries = new Entry[] { Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c)) };
        final ObjectId treeId = ra.writeTree(List.of(entries), c);
        final ObjectId commit1 = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeId, IDENT, IDENT, "first", c);
        flush();

        assertNotEquals(ObjectId.zeroId(), commit1);
        assertEquals(Constants.OBJ_COMMIT, ra.getObjectType(commit1));

        final ObjectId commit2 = ra.writeCommit(new ObjectId[] { commit1 }, treeId, IDENT, IDENT, "second", c);
        flush();
        assertNotEquals(commit1, commit2);
    }

    @Test
    public void testCommitWithExtraHeaders() throws Exception {
        // Verify that extra headers (change-id, custom) survive a write → extract → rewrite cycle
        // byte-for-byte. CommitBuilder cannot represent these headers, so this exercises the
        // raw-byte path in writeCommit / extractExtraHeaders.
        final Entry[] entries = new Entry[] { Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c)) };
        final ObjectId treeId = ra.writeTree(List.of(entries), c);
        final byte[] extra = ("change-id Iabcdef0123456789abcdef0123456789abcdef01\n"
                + "x-custom-header some-value\n").getBytes(StandardCharsets.UTF_8);
        final ObjectId commitId = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeId, IDENT, IDENT,
                extra, "hello", null, c);
        flush();

        // Parse and verify
        try (final RevWalk walk = new RevWalk(repo)) {
            final RevCommit parsed = walk.parseCommit(commitId);
            final String raw = new String(parsed.getRawBuffer(), StandardCharsets.UTF_8);
            assertTrue(raw.contains("\nchange-id Iabcdef0123456789abcdef0123456789abcdef01\n"));
            assertTrue(raw.contains("\nx-custom-header some-value\n"));

            // Extracted extras match what we wrote
            assertArrayEquals(extra, RawGitObjectCodec.extractExtraHeaders(parsed));

            // Rewriting the same commit with the extracted bytes yields the same ID
            final ObjectId commitId2 = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeId, IDENT, IDENT,
                    RawGitObjectCodec.extractExtraHeaders(parsed), parsed.getFullMessage(), parsed.getEncoding(), c);
            flush();
            assertEquals(commitId, commitId2);
        }
    }

    @Test
    public void testCommitNonUtf8EncodingRoundTrip() throws Exception {
        // A commit declaring a non-UTF-8 encoding must round-trip identically: JGit decodes the
        // author name and message using the encoding header, and writeCommit re-encodes with the
        // same charset (commit.getEncoding()), so the object ID is preserved.
        final Charset latin1 = Charset.forName("ISO-8859-1");
        final ObjectId treeId = ra.writeTree(List.of(), c);

        // Build a raw commit by hand: author "Müller", message "Grüße", encoding ISO-8859-1
        final String ident = "Müller <m@example.com> 1700000000 +0900";
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(("tree " + treeId.name() + "\n").getBytes(StandardCharsets.US_ASCII));
        raw.write("author ".getBytes(StandardCharsets.US_ASCII));
        raw.write(ident.getBytes(latin1));
        raw.write('\n');
        raw.write("committer ".getBytes(StandardCharsets.US_ASCII));
        raw.write(ident.getBytes(latin1));
        raw.write('\n');
        raw.write("encoding ISO-8859-1\n".getBytes(StandardCharsets.US_ASCII));
        raw.write('\n');
        raw.write("Grüße\n".getBytes(latin1));
        final ObjectId srcId = ra.insert(ins -> ins.insert(Constants.OBJ_COMMIT, raw.toByteArray()), c);
        flush();

        try (final RevWalk walk = new RevWalk(repo)) {
            final RevCommit src = walk.parseCommit(srcId);
            assertEquals(latin1, src.getEncoding());

            // Rewrite through the extra-headers path the way RepositoryRewriter does
            final ObjectId rewritten = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeId,
                    src.getAuthorIdent(), src.getCommitterIdent(),
                    RawGitObjectCodec.extractExtraHeaders(src), src.getFullMessage(), src.getEncoding(), c);
            flush();
            assertEquals(srcId, rewritten);
        }
    }

    @Test
    public void testTagWithLegacyEncodingRoundTrip() throws Exception {
        // A tag with a legacy (Latin-1) tagger name and message has no encoding header, so the
        // TagBuilder path re-encodes to UTF-8 and changes the SHA. The raw-byte writeTag must
        // preserve the tagger/message bytes and reproduce the original tag object ID.
        final Charset latin1 = Charset.forName("ISO-8859-1");
        final ObjectId blobId = ra.writeBlob(HELLO, c);

        final String ident = "Müller <m@example.com> 1700000000 +0900";
        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(("object " + blobId.name() + "\n").getBytes(StandardCharsets.US_ASCII));
        raw.write("type blob\n".getBytes(StandardCharsets.US_ASCII));
        raw.write("tag v1.0\n".getBytes(StandardCharsets.US_ASCII));
        raw.write("tagger ".getBytes(StandardCharsets.US_ASCII));
        raw.write(ident.getBytes(latin1));
        raw.write('\n');
        raw.write('\n');
        raw.write("Grüße\n".getBytes(latin1));
        final ObjectId srcId = ra.insert(ins -> ins.insert(Constants.OBJ_TAG, raw.toByteArray()), c);
        flush();

        try (final RevWalk walk = new RevWalk(repo)) {
            final RevTag src = walk.parseTag(srcId);

            // TagBuilder path corrupts the SHA (re-encodes Latin-1 to UTF-8)
            final ObjectId viaBuilder = ra.writeTag(blobId, Constants.OBJ_BLOB, src.getTagName(),
                    src.getTaggerIdent(), src.getFullMessage(), c);
            flush();
            assertNotEquals(srcId, viaBuilder);

            // Raw-byte path preserves the bytes and reproduces the original ID
            final ObjectId viaRaw = ra.writeTag(blobId, Constants.OBJ_BLOB, src.getTagName(),
                    RawGitObjectCodec.rawTagger(src), RawGitObjectCodec.extractTagHeaders(src),
                    RawGitObjectCodec.rawTagMessage(src), c);
            flush();
            assertEquals(srcId, viaRaw);
        }
    }

    @Test
    public void testTagWithExtraHeaderRoundTrip() throws Exception {
        // A tag with a header after 'tagger' (here 'encoding') is non-conformant — git mktag won't
        // create one — but git reads/clones such an object, so if one is ever present the raw path
        // must reproduce it byte-for-byte. We have no evidence such tags occur in practice; this
        // pins the unconditional byte-for-byte invariant, symmetric with commit extra headers.
        final Charset latin1 = Charset.forName("ISO-8859-1");
        final ObjectId blobId = ra.writeBlob(HELLO, c);

        final ByteArrayOutputStream raw = new ByteArrayOutputStream();
        raw.write(("object " + blobId.name() + "\n").getBytes(StandardCharsets.US_ASCII));
        raw.write("type blob\n".getBytes(StandardCharsets.US_ASCII));
        raw.write("tag v1.0\n".getBytes(StandardCharsets.US_ASCII));
        raw.write("tagger Test <t@example.com> 1700000000 +0900\n".getBytes(StandardCharsets.US_ASCII));
        raw.write("encoding ISO-8859-1\n".getBytes(StandardCharsets.US_ASCII));
        raw.write('\n');
        raw.write("Grüße\n".getBytes(latin1));
        final ObjectId srcId = ra.insert(ins -> ins.insert(Constants.OBJ_TAG, raw.toByteArray()), c);
        flush();

        try (final RevWalk walk = new RevWalk(repo)) {
            final RevTag src = walk.parseTag(srcId);
            assertArrayEquals("encoding ISO-8859-1\n".getBytes(StandardCharsets.US_ASCII),
                    RawGitObjectCodec.extractTagHeaders(src));
            final ObjectId rebuilt = ra.writeTag(blobId, Constants.OBJ_BLOB, src.getTagName(),
                    RawGitObjectCodec.rawTagger(src), RawGitObjectCodec.extractTagHeaders(src),
                    RawGitObjectCodec.rawTagMessage(src), c);
            flush();
            assertEquals(srcId, rebuilt);
        }
    }

    @Test
    public void testExtractExtraHeadersEmpty() throws Exception {
        // A commit without any extra headers should yield an empty byte array.
        final Entry[] entries = new Entry[] { Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c)) };
        final ObjectId treeId = ra.writeTree(List.of(entries), c);
        final ObjectId commitId = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeId, IDENT, IDENT,
                null, "hello", null, c);
        flush();

        try (final RevWalk walk = new RevWalk(repo)) {
            final RevCommit parsed = walk.parseCommit(commitId);
            assertEquals(0, RawGitObjectCodec.extractExtraHeaders(parsed).length);
        }
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

                Entry[] entries = new Entry[] { Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c)) };
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
        Entry[] entries = new Entry[] { Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c)) };
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
        Entry[] entries = new Entry[] { Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c)) };
        ObjectId treeId = ra.writeTree(List.of(entries), c);
        final ObjectId commitId = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeId, IDENT, IDENT, "hello", c);
        flush();

        ra.applyRefUpdate(new RefEntry("refs/heads/temp", commitId));
        assertNotNull(ra.getRef("refs/heads/temp"));

        ra.applyRefDelete(new RefEntry("refs/heads/temp", commitId));
        assertNull(ra.getRef("refs/heads/temp"));
    }

    @Test
    public void testGetRefTargetResolvesSymbolicAndPeelsTags() {
        final ObjectId treeId = ra.writeTree(List.of(Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c))), c);
        final ObjectId commitId = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeId, IDENT, IDENT, "hello", c);
        final ObjectId tagId = ra.writeTag(commitId, Constants.OBJ_COMMIT, "v1", IDENT, "release", c);
        flush();

        ra.applyRefUpdate(new RefEntry("refs/heads/main", commitId));
        ra.applyRefUpdate(new RefEntry("refs/tags/v1", tagId));
        ra.applyRefUpdate(new RefEntry("HEAD", "refs/heads/main"));

        // direct ref to a commit: resolves to itself
        assertEquals(commitId, ra.getRefTarget(new RefEntry("refs/heads/main", commitId)));
        // annotated tag: peeled to the underlying commit
        assertEquals(commitId, ra.getRefTarget(new RefEntry("refs/tags/v1", tagId)));
        // symbolic ref: followed to its target's commit
        final RefEntry head = new RefEntry(ra.getRef("HEAD"));
        assertTrue(head.isSymbolic());
        assertEquals(commitId, ra.getRefTarget(head));
    }

    // --- Notes ---

    @Test
    public void testNotes() {
        Entry[] entries = new Entry[] { Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c)) };
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

        // no duplicates: unchanged
        assertEquals("hello", RepositoryAccess.resolveNameConflicts(
                List.of(Entry.of(BLOB_MODE, "hello", id1))).get(0).name);

        // empty list
        assertTrue(RepositoryAccess.resolveNameConflicts(List.of()).isEmpty());

        // duplicate gets @2 suffix, preserving id and order
        final List<Entry> dup2 = RepositoryAccess.resolveNameConflicts(List.of(
                Entry.of(BLOB_MODE, "hello", id1),
                Entry.of(BLOB_MODE, "world", id2),
                Entry.of(BLOB_MODE, "hello", id3)));
        assertEquals("hello", dup2.get(0).name);
        assertEquals(id1, dup2.get(0).id);
        assertEquals("world", dup2.get(1).name);
        assertEquals("hello@2", dup2.get(2).name);
        assertEquals(id3, dup2.get(2).id);

        // triple duplicate gets incrementing suffixes
        final List<Entry> dup3 = RepositoryAccess.resolveNameConflicts(List.of(
                Entry.of(BLOB_MODE, "hello", id1),
                Entry.of(BLOB_MODE, "hello", id2),
                Entry.of(BLOB_MODE, "hello", id3)));
        assertEquals("hello", dup3.get(0).name);
        assertEquals("hello@2", dup3.get(1).name);
        assertEquals("hello@3", dup3.get(2).name);

        // directory preserved
        final List<Entry> withDir = RepositoryAccess.resolveNameConflicts(List.of(
                Entry.of(BLOB_MODE, "hello", id1, "dir"),
                Entry.of(BLOB_MODE, "hello", id2, "dir")));
        assertEquals("dir", withDir.get(1).directory);
        assertEquals(id2, withDir.get(1).id);
    }

    @Test
    public void testWalk() {
        assertNotNull(ra.walk());
    }

    @Test
    public void testCollectCommits() {
        Entry[] entries = new Entry[] { Entry.of(BLOB_MODE, "hello.txt", ra.writeBlob(HELLO, c)) };
        ObjectId treeId = ra.writeTree(List.of(entries), c);
        final ObjectId commit1 = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeId, IDENT, IDENT, "first", c);
        final ObjectId commit2 = ra.writeCommit(new ObjectId[] { commit1 }, treeId, IDENT, IDENT, "second", c);
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

    @Test
    public void testInsertRetriesAConcurrentObjectWrite() {
        // a losing race against another thread writing the same object surfaces as
        // ObjectWritingException (eclipse-jgit/jgit#288); the retry finds the object in place
        final int[] attempts = {0};
        final ObjectId id = ra.insert(ins -> {
            if (attempts[0]++ == 0) {
                throw new ObjectWritingException("Unable to create new object: whatever");
            }
            return ins.insert(Constants.OBJ_BLOB, HELLO);
        }, c);
        flush();

        assertEquals(2, attempts[0]);
        assertArrayEquals(HELLO, ra.readBlob(id));
    }

    @Test
    public void testInsertDoesNotRetryOtherFailures() {
        // only the concurrent-write collision is spurious; anything else is the caller's to see
        final int[] attempts = {0};
        final UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> ra.insert(ins -> {
            attempts[0]++;
            throw new IOException("disk on fire");
        }, c));

        assertEquals(1, attempts[0]);
        assertEquals("disk on fire", e.getCause().getMessage());
    }
}
