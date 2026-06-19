package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SubdirTest {
    static RepositoryAccess source, result;

    @BeforeAll
    static void setUp() throws IOException {
        // The sample tree is { README.md, com/example/Hello.java }; re-root at "com".
        source = TestRepo.createSample();
        final Subdir app = new Subdir();
        app.path = "com";
        result = TestRepo.rewrite(source, app);
    }

    @AfterAll
    static void tearDown() {
        result.close();
        source.close();
    }

    @Test
    public void testRootBecomesTheSubdirectory() {
        final List<RevCommit> commits = result.collectCommits("refs/heads/main");
        final RevCommit head = commits.get(commits.size() - 1);
        final List<String> names = result.readTree(head.getTree().getId(), null).stream().map(e -> e.name).toList();
        assertTrue(names.contains("example"));   // com's child is now at the root
        assertFalse(names.contains("com"));       // the old root level is gone
        assertFalse(names.contains("README.md"));
    }

    @Test
    public void testCommitCountIsPreserved() {
        // @subdir only re-roots trees; it does not touch the commit graph.
        assertEquals(source.collectCommits("refs/heads/main").size(),
                result.collectCommits("refs/heads/main").size());
    }
}
