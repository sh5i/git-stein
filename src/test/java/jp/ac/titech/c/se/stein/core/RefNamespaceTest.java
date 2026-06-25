package jp.ac.titech.c.se.stein.core;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.app.Identity;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.SymbolicRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RefNamespaceTest {
    static RepositoryAccess ra;
    static ObjectId c1, c2;

    @BeforeAll
    static void setUp() throws IOException {
        // The whole input history lives under refs/xyz/; an @id rewrite maps refs/xyz/ -> refs/abc/
        // within the same repository (same object store, different ref namespace).
        ra = TestRepo.create();
        try (final ObjectInserter inserter = ra.repo.newObjectInserter()) {
            final Context c = Context.init().with(Context.Key.inserter, inserter);
            final PersonIdent who = new PersonIdent("T", "t@example.com", 0, 0);
            final ObjectId treeA = ra.writeTree(List.of(Entry.ofBlob("f.txt", ra.writeBlob("v1".getBytes(), c))), c);
            final ObjectId treeB = ra.writeTree(List.of(Entry.ofBlob("f.txt", ra.writeBlob("v2".getBytes(), c))), c);
            c1 = ra.writeCommit(RepositoryAccess.NO_PARENTS, treeA, who, who, "first", c);
            c2 = ra.writeCommit(new ObjectId[]{c1}, treeB, who, who, "second", c);
            inserter.flush();
            ra.applyRefUpdate(RefEntry.of("refs/xyz/refs/heads/main", c2));
            ra.applyRefUpdate(RefEntry.of("refs/xyz/refs/tags/v1", c1));
            ra.applyRefUpdate(RefEntry.of("refs/xyz/HEAD", "refs/xyz/refs/heads/main"));
        }

        final Identity rewriter = new Identity();
        rewriter.setConfig(new Application.Config());
        rewriter.initialize(ra.repo, new RefNamespace("refs/xyz/"), ra.repo, new RefNamespace("refs/abc/"));
        rewriter.useDefaultScope();
        rewriter.rewrite(Context.init());
    }

    @AfterAll
    static void tearDown() {
        ra.close();
    }

    private static Ref exact(final String name) {
        return Try.io(() -> ra.repo.getRefDatabase().exactRef(name));
    }

    @Test
    public void testRefsRoundTripIntoTargetNamespace() {
        // identity rewrite keeps the object ids, re-presented under refs/abc/
        assertEquals(c2, exact("refs/abc/refs/heads/main").getObjectId());
        assertEquals(c1, exact("refs/abc/refs/tags/v1").getObjectId());

        final Ref head = exact("refs/abc/HEAD");
        assertTrue(head.isSymbolic(), "HEAD should stay symbolic");
        assertEquals("refs/abc/refs/heads/main", head.getTarget().getName());
    }

    @Test
    public void testSourceNamespaceIsUntouched() {
        // isOverwriting is false (source and target namespaces differ), so refs/xyz/ is not deleted or renamed
        assertEquals(c2, exact("refs/xyz/refs/heads/main").getObjectId());
        assertEquals(c1, exact("refs/xyz/refs/tags/v1").getObjectId());
        assertNotNull(exact("refs/xyz/HEAD"));
    }

    @Test
    public void testNameTranslationAndLogicalEntryReading() {
        final RefNamespace ns = new RefNamespace("refs/xyz/");
        assertEquals("refs/xyz/refs/heads/main", ns.toStored("refs/heads/main"));
        assertEquals("refs/heads/main", ns.toLogical("refs/xyz/refs/heads/main"));

        // toLogicalEntry reads a stored JGit ref into a logical entry (direct ref)
        final Ref storedDirect = new ObjectIdRef.Unpeeled(Ref.Storage.LOOSE, "refs/xyz/refs/heads/main", c1);
        final RefEntry direct = ns.toLogicalEntry(storedDirect);
        assertEquals("refs/heads/main", direct.name);
        assertEquals(c1, direct.id);

        // a symbolic ref has its target name translated too
        final RefEntry symbolic = ns.toLogicalEntry(new SymbolicRef("refs/xyz/HEAD", storedDirect));
        assertTrue(symbolic.isSymbolic());
        assertEquals("HEAD", symbolic.name);
        assertEquals("refs/heads/main", symbolic.target);

        // toStoredRef is the inverse direction (logical RefEntry -> stored JGit ref)
        final Ref backDirect = ns.toStoredRef(direct);
        assertEquals("refs/xyz/refs/heads/main", backDirect.getName());
        assertEquals(c1, backDirect.getObjectId());

        final Ref backSymbolic = ns.toStoredRef(symbolic);
        assertTrue(backSymbolic.isSymbolic());
        assertEquals("refs/xyz/HEAD", backSymbolic.getName());
        assertEquals("refs/xyz/refs/heads/main", backSymbolic.getTarget().getName());
    }
}
