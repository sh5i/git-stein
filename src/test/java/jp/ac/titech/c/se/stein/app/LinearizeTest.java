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

public class LinearizeTest {
    static RepositoryAccess source, result;

    @BeforeAll
    static void setUp() throws IOException {
        // base -> main2 and base -> side, then merge(main2, side) on refs/heads/main
        source = TestRepo.create();
        try (final ObjectInserter inserter = source.repo.newObjectInserter()) {
            final Context c = Context.init().with(Context.Key.inserter, inserter);
            final PersonIdent who = new PersonIdent("T", "t@example.com", 0, 0);

            final ObjectId base = source.writeCommit(RepositoryAccess.NO_PARENTS, tree("0", c), who, who, "base", c);
            final ObjectId main2 = source.writeCommit(new ObjectId[]{base}, tree("1", c), who, who, "main2", c);
            final ObjectId side = source.writeCommit(new ObjectId[]{base}, tree("2", c), who, who, "side", c);
            final ObjectId merge = source.writeCommit(new ObjectId[]{main2, side}, tree("3", c), who, who, "merge", c);
            inserter.flush();
            source.applyRefUpdate(new RefEntry("refs/heads/main", merge));
            source.applyRefUpdate(new RefEntry("HEAD", "refs/heads/main"));
        }
        result = TestRepo.rewrite(source, new Linearize());
    }

    private static ObjectId tree(final String content, final Context c) {
        return source.writeTree(List.of(Entry.ofBlob("f.txt", source.writeBlob(content.getBytes(), c))), c);
    }

    @AfterAll
    static void tearDown() {
        result.close();
        source.close();
    }

    @Test
    public void testMergeKeepsOnlyFirstParentAndSideBranchDropsOut() {
        final List<RevCommit> commits = result.collectCommits("refs/heads/main");
        final List<String> messages = commits.stream().map(RevCommit::getFullMessage).toList();
        assertEquals(3, messages.size());
        assertTrue(messages.contains("base"));
        assertTrue(messages.contains("main2"));
        assertTrue(messages.contains("merge"));
        assertFalse(messages.contains("side")); // reachable only via the second parent, so dropped

        final RevCommit merge = commits.stream().filter(rc -> rc.getFullMessage().equals("merge")).findFirst().orElseThrow();
        assertEquals(1, merge.getParentCount());
    }
}
