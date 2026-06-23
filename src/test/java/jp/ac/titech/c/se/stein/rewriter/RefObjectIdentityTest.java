package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.app.Identity;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RefEntry;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevTag;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that refs and tags pointing directly to a blob or tree that no commit reaches (as
 * the Linux kernel's {@code refs/tags/v2.6.11} points to a tree) are reproduced byte-for-byte by
 * the identity rewriter. These objects exercise the tree and blob branches of
 * {@link RepositoryRewriter#rewriteRefObject}, which route them through the same rewriting as
 * commit contents rather than copying them verbatim.
 */
public class RefObjectIdentityTest {
    static RepositoryAccess source, result;
    static ObjectId orphanBlob, orphanTree;

    @BeforeAll
    static void setUp() throws IOException {
        source = TestRepo.create();
        try (final ObjectInserter inserter = source.repo.newObjectInserter()) {
            final Context c = Context.init().with(Context.Key.inserter, inserter);
            final PersonIdent who = new PersonIdent("Test", "test@example.com", 0, 0);

            // a minimal commit so the history is non-empty
            final ObjectId fileBlob = source.writeBlob("hello\n".getBytes(), c);
            final ObjectId rootTree = source.writeTree(List.of(Entry.of(FileMode.REGULAR_FILE.getBits(), "a.txt", fileBlob)), c);
            final ObjectId commit = source.writeCommit(RepositoryAccess.NO_PARENTS, rootTree, who, who, "initial", c);

            // objects reachable only through tags, not through any commit
            orphanBlob = source.writeBlob("orphan\n".getBytes(), c);
            orphanTree = source.writeTree(List.of(Entry.of(FileMode.REGULAR_FILE.getBits(), "key.txt", orphanBlob)), c);
            final ObjectId blobTag = source.writeTag(orphanBlob, Constants.OBJ_BLOB, "blobtag", who, "blob tag", c);
            final ObjectId treeTag = source.writeTag(orphanTree, Constants.OBJ_TREE, "treetag", who, "tree tag", c);
            inserter.flush();

            source.applyRefUpdate(new RefEntry("refs/heads/main", commit));
            source.applyRefUpdate(new RefEntry("refs/tags/blobtag", blobTag));   // annotated -> orphan blob
            source.applyRefUpdate(new RefEntry("refs/tags/treetag", treeTag));   // annotated -> orphan tree
            source.applyRefUpdate(new RefEntry("refs/tags/lwblob", orphanBlob)); // lightweight -> orphan blob
        }
        result = TestRepo.rewrite(source, new Identity());
    }

    @AfterAll
    static void tearDown() {
        result.close();
        source.close();
    }

    @Test
    public void testAnnotatedTagToOrphanBlob() {
        assertTagTargetPreserved("refs/tags/blobtag", Constants.OBJ_BLOB, orphanBlob);
        assertArrayEquals("orphan\n".getBytes(), result.readBlob(orphanBlob));
    }

    @Test
    public void testAnnotatedTagToOrphanTree() {
        assertTagTargetPreserved("refs/tags/treetag", Constants.OBJ_TREE, orphanTree);
    }

    @Test
    public void testLightweightTagToOrphanBlob() {
        assertEquals(orphanBlob, result.getRef("refs/tags/lwblob").id);
        assertEquals(Constants.OBJ_BLOB, result.getObjectType(orphanBlob));
    }

    @Test
    public void testBlobDeletionDiscardsBlobRefsButKeepsEmptiedTree() {
        // Deleting every blob drops the orphan blob (no object remains, so its refs are discarded)
        // but empties the orphan tree into a valid empty tree (still an object, so its tag is kept).
        final RepositoryAccess deleted = TestRepo.rewrite(source, new BlobDeleter());
        try {
            assertNull(deleted.getRef("refs/tags/blobtag"));    // blob deleted: no object -> discarded
            assertNull(deleted.getRef("refs/tags/lwblob"));     // same, via a lightweight tag
            assertNotNull(deleted.getRef("refs/tags/treetag")); // emptied tree -> empty tree -> kept
            assertNotNull(deleted.getRef("refs/heads/main"));
        } finally {
            deleted.close();
        }
    }

    @Test
    public void testRefBlobExpandingToManyEntriesBecomesATree() {
        // When a ref's blob rewrites into several entries, they are wrapped in a tree the ref
        // points to (preserving the full output), rather than reverting to the original blob.
        final RepositoryAccess exploded = TestRepo.rewrite(source, new BlobExploder());
        try {
            final RevTag tag = exploded.parseTag(exploded.getRef("refs/tags/blobtag").id);
            assertEquals(Constants.OBJ_TREE, exploded.getObjectType(tag.getObject().getId()));
            assertEquals(Constants.OBJ_TREE, exploded.getObjectType(exploded.getRef("refs/tags/lwblob").id));
        } finally {
            exploded.close();
        }
    }

    @Test
    public void testOverridingRefTreeCanDiscardNonCommitTrees() {
        // rewriteRefTree is the seam for dropping a tree a ref or tag points to: overriding it to
        // return ZERO discards the tree's tag while leaving blob refs untouched.
        final RepositoryAccess discarded = TestRepo.rewrite(source, new RefTreeDiscarder());
        try {
            assertNull(discarded.getRef("refs/tags/treetag"));
            assertNotNull(discarded.getRef("refs/tags/blobtag"));
        } finally {
            discarded.close();
        }
    }

    // The tag object id is identical (so its target is preserved by content), and the target is
    // present in the result with the expected id and type.
    private void assertTagTargetPreserved(final String ref, final int targetType, final ObjectId expectedTarget) {
        assertEquals(source.getRef(ref).id, result.getRef(ref).id);
        final RevTag tag = result.parseTag(result.getRef(ref).id);
        assertEquals(expectedTarget, tag.getObject().getId());
        assertEquals(targetType, result.getObjectType(tag.getObject().getId()));
    }

    private static class BlobDeleter extends RepositoryRewriter {
        @Override
        protected AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
            return AnyHotEntry.empty();
        }
    }

    private static class RefTreeDiscarder extends RepositoryRewriter {
        @Override
        protected ObjectId rewriteRefTree(final ObjectId id, final Context c) {
            return ZERO;
        }
    }

    private static class BlobExploder extends RepositoryRewriter {
        @Override
        protected AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
            return AnyHotEntry.set(
                    HotEntry.ofBlob(entry.getName() + ".a", entry.getBlob()),
                    HotEntry.ofBlob(entry.getName() + ".b", entry.getBlob()));
        }
    }
}
