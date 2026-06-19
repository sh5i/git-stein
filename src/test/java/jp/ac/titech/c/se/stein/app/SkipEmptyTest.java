package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RefEntry;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SkipEmptyTest {
    static RepositoryAccess source, result;

    @BeforeAll
    static void setUp() throws IOException {
        // first (tree A) -> empty (tree A again) -> third (tree B)
        source = TestRepo.create();
        try (final ObjectInserter inserter = source.repo.newObjectInserter()) {
            final Context c = Context.init().with(Context.Key.inserter, inserter);
            final PersonIdent who = new PersonIdent("T", "t@example.com", 0, 0);

            final ObjectId treeA = source.writeTree(List.of(Entry.ofBlob("f.txt", source.writeBlob("v1".getBytes(), c))), c);
            final ObjectId treeB = source.writeTree(List.of(Entry.ofBlob("f.txt", source.writeBlob("v2".getBytes(), c))), c);
            final ObjectId c1 = source.writeCommit(RepositoryAccess.NO_PARENTS, treeA, who, who, "first", c);
            final ObjectId c2 = source.writeCommit(new ObjectId[]{c1}, treeA, who, who, "empty", c);
            final ObjectId c3 = source.writeCommit(new ObjectId[]{c2}, treeB, who, who, "third", c);
            inserter.flush();
            source.applyRefUpdate(new RefEntry("refs/heads/main", c3));
            source.applyRefUpdate(new RefEntry("HEAD", "refs/heads/main"));
        }
        result = TestRepo.rewrite(source, new SkipEmpty());
    }

    @AfterAll
    static void tearDown() {
        result.close();
        source.close();
    }

    @Test
    public void testEmptyCommitIsElided() {
        final List<String> messages = result.collectCommits("refs/heads/main").stream()
                .map(RevCommit::getFullMessage).toList();
        assertEquals(2, messages.size());
        assertTrue(messages.contains("first"));
        assertTrue(messages.contains("third"));
        assertFalse(messages.contains("empty"));
    }

    @Test
    public void testSurvivingCommitsAreReparented() {
        // "third" should now sit directly on "first" since "empty" was elided.
        final RevCommit head = result.collectCommits("refs/heads/main").stream()
                .filter(rc -> rc.getFullMessage().equals("third")).findFirst().orElseThrow();
        assertEquals(1, head.getParentCount());
        final RevCommit parent = result.collectCommits("refs/heads/main").stream()
                .filter(rc -> rc.getId().equals(head.getParent(0))).findFirst().orElseThrow();
        assertEquals("first", parent.getFullMessage());
    }
}
