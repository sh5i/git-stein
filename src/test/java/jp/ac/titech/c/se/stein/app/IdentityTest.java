package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class IdentityTest {
    static TestRepo source;
    static TestRepo.RewriteResult result;

    @BeforeAll
    static void setUp() throws IOException {
        source = TestRepo.create();
        result = source.rewrite(new Identity());
    }

    @AfterAll
    static void tearDown() {
        result.close();
        source.close();
    }

    @Test
    public void testCommitMessages() {
        final List<RevCommit> commits = result.access.collectCommits("refs/heads/main");
        assertEquals("initial", commits.get(0).getFullMessage());
        assertEquals("add features", commits.get(1).getFullMessage());
        assertEquals("modern syntax", commits.get(2).getFullMessage());
    }

    @Test
    public void testRefs() {
        final Ref sourceMain = source.access.getRef("refs/heads/main");
        final Ref targetMain = result.access.getRef("refs/heads/main");
        assertNotNull(targetMain);
        assertEquals(sourceMain.getObjectId(), targetMain.getObjectId());
    }

    @Test
    public void testTagRef() {
        final Ref sourceTag = source.access.getRef("refs/tags/v1.0");
        final Ref targetTag = result.access.getRef("refs/tags/v1.0");
        assertNotNull(targetTag);
        assertEquals(sourceTag.getObjectId(), targetTag.getObjectId());
    }

    @Test
    public void testAuthorPreserved() {
        final List<RevCommit> commits = result.access.collectCommits("refs/heads/main");
        assertEquals("Test Author", commits.get(0).getAuthorIdent().getName());
        assertEquals("author@example.com", commits.get(0).getAuthorIdent().getEmailAddress());
        assertEquals("Test Committer", commits.get(0).getCommitterIdent().getName());
    }

    @Test
    public void testCommitIds() {
        final List<RevCommit> sourceCommits = source.access.collectCommits("refs/heads/main");
        final List<RevCommit> targetCommits = result.access.collectCommits("refs/heads/main");

        assertEquals(sourceCommits.size(), targetCommits.size());
        // Identity rewriter should produce identical commit IDs
        for (int i = 0; i < sourceCommits.size(); i++) {
            assertEquals(sourceCommits.get(i).getId(), targetCommits.get(i).getId());
        }
    }
}
