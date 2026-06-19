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
import picocli.CommandLine;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FilterCommitTest {
    static RepositoryAccess source, result;

    @BeforeAll
    static void setUp() throws IOException {
        // Alice "a1" -> Bob "b" -> Alice "a2"; keep Alice only.
        source = TestRepo.create();
        try (final ObjectInserter inserter = source.repo.newObjectInserter()) {
            final Context c = Context.init().with(Context.Key.inserter, inserter);
            final PersonIdent alice = new PersonIdent("Alice", "alice@x", 0, 0);
            final PersonIdent bob = new PersonIdent("Bob", "bob@x", 0, 0);

            final ObjectId t1 = source.writeTree(List.of(Entry.ofBlob("f", source.writeBlob("1".getBytes(), c))), c);
            final ObjectId t2 = source.writeTree(List.of(Entry.ofBlob("f", source.writeBlob("2".getBytes(), c))), c);
            final ObjectId t3 = source.writeTree(List.of(Entry.ofBlob("f", source.writeBlob("3".getBytes(), c))), c);
            final ObjectId c1 = source.writeCommit(RepositoryAccess.NO_PARENTS, t1, alice, alice, "a1", c);
            final ObjectId c2 = source.writeCommit(new ObjectId[]{c1}, t2, bob, bob, "b", c);
            final ObjectId c3 = source.writeCommit(new ObjectId[]{c2}, t3, alice, alice, "a2", c);
            inserter.flush();
            source.applyRefUpdate(new RefEntry("refs/heads/main", c3));
            source.applyRefUpdate(new RefEntry("HEAD", "refs/heads/main"));
        }
        final FilterCommit app = new FilterCommit();
        app.author = Pattern.compile("Alice");
        result = TestRepo.rewrite(source, app);
    }

    @AfterAll
    static void tearDown() {
        result.close();
        source.close();
    }

    @Test
    public void testNonMatchingCommitsAreDropped() {
        final List<String> messages = result.collectCommits("refs/heads/main").stream()
                .map(RevCommit::getFullMessage).toList();
        assertEquals(2, messages.size());
        assertTrue(messages.contains("a1"));
        assertTrue(messages.contains("a2"));
        assertFalse(messages.contains("b")); // Bob's commit dropped
    }

    @Test
    public void testMatchingCommitsReparentAcrossTheGap() {
        final RevCommit a2 = result.collectCommits("refs/heads/main").stream()
                .filter(rc -> rc.getFullMessage().equals("a2")).findFirst().orElseThrow();
        final RevCommit parent = result.collectCommits("refs/heads/main").stream()
                .filter(rc -> rc.getId().equals(a2.getParent(0))).findFirst().orElseThrow();
        assertEquals("a1", parent.getFullMessage());
    }

    @Test
    public void testDroppingAMergeSplicesChildrenOntoBothParents() throws IOException {
        // A -> C and A -> B both kept; merge M(C, B) dropped; child D should splice onto C and B.
        final RepositoryAccess src = TestRepo.create();
        try (final ObjectInserter inserter = src.repo.newObjectInserter()) {
            final Context c = Context.init().with(Context.Key.inserter, inserter);
            final PersonIdent keep = new PersonIdent("Keeper", "k@x", 0, 0);
            final PersonIdent drop = new PersonIdent("Dropme", "d@x", 0, 0);

            final ObjectId a = src.writeCommit(RepositoryAccess.NO_PARENTS, tree(src, "a", c), keep, keep, "A", c);
            final ObjectId cc = src.writeCommit(new ObjectId[]{a}, tree(src, "c", c), keep, keep, "C", c);
            final ObjectId b = src.writeCommit(new ObjectId[]{a}, tree(src, "b", c), keep, keep, "B", c);
            final ObjectId m = src.writeCommit(new ObjectId[]{cc, b}, tree(src, "m", c), drop, drop, "M", c);
            final ObjectId d = src.writeCommit(new ObjectId[]{m}, tree(src, "d", c), keep, keep, "D", c);
            inserter.flush();
            src.applyRefUpdate(new RefEntry("refs/heads/main", d));
            src.applyRefUpdate(new RefEntry("HEAD", "refs/heads/main"));
        }

        final FilterCommit app = new FilterCommit();
        app.author = Pattern.compile("Keeper");
        final RepositoryAccess res = TestRepo.rewrite(src, app);
        try {
            final List<RevCommit> commits = res.collectCommits("refs/heads/main");
            final List<String> messages = commits.stream().map(RevCommit::getFullMessage).toList();
            assertEquals(4, messages.size()); // A, B, C, D survive
            assertFalse(messages.contains("M")); // the merge was dropped

            final RevCommit head = commits.stream()
                    .filter(rc -> rc.getFullMessage().equals("D")).findFirst().orElseThrow();
            assertEquals(2, head.getParentCount()); // D spliced onto both of M's parents
            final List<String> parents = commits.stream()
                    .filter(rc -> rc.getId().equals(head.getParent(0)) || rc.getId().equals(head.getParent(1)))
                    .map(RevCommit::getFullMessage).toList();
            assertTrue(parents.contains("C"));
            assertTrue(parents.contains("B"));
        } finally {
            res.close();
            src.close();
        }
    }

    @Test
    public void testPicocliConvertsRegexAndInstantOptions() {
        final FilterCommit app = new FilterCommit();
        new CommandLine(app).parseArgs("--author", "Alice", "--since", "2024-01-01T00:00:00Z");
        assertEquals("Alice", app.author.pattern());
        assertEquals(Instant.parse("2024-01-01T00:00:00Z"), app.since);
    }

    private static ObjectId tree(final RepositoryAccess repo, final String content, final Context c) {
        return repo.writeTree(List.of(Entry.ofBlob("f", repo.writeBlob(content.getBytes(), c))), c);
    }
}
