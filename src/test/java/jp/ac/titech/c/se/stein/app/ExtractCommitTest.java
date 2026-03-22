package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.testing.TemporaryRepositoryAccess;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExtractCommitTest {
    static TestRepo source;

    @BeforeAll
    static void setUp() throws IOException {
        source = TestRepo.create();
    }

    @AfterAll
    static void tearDown() {
        source.close();
    }

    TemporaryRepositoryAccess extract(String commitSpec) {
        final ExtractCommit extractor = new ExtractCommit();
        extractor.targetCommitSpec = commitSpec;
        extractor.setConfig(new Application.Config());

        final Repository targetRepo = new InMemoryRepository(new DfsRepositoryDescription("target"));
        extractor.initialize(source.access.repo, targetRepo);
        extractor.rewrite(Context.init());
        return new TemporaryRepositoryAccess(targetRepo);
    }

    @Test
    public void testExtractLastCommit() {
        // extract commit3 → should get commit2 + commit3
        try (RepositoryAccess result = extract(source.commit3.name())) {
            final List<RevCommit> commits = result.collectCommits("refs/heads/main");
            assertEquals(2, commits.size());
            assertEquals("add features", commits.get(0).getFullMessage());
            assertEquals("modern syntax", commits.get(1).getFullMessage());

            // parent commit should have no parents (root)
            assertEquals(0, commits.get(0).getParentCount());
        }
    }

    @Test
    public void testExtractMiddleCommit() {
        // extract commit2 → should get commit1 + commit2
        try (RepositoryAccess result = extract(source.commit2.name())) {
            final List<RevCommit> commits = result.collectCommits("refs/heads/main");
            assertEquals(2, commits.size());
            assertEquals("initial", commits.get(0).getFullMessage());
            assertEquals("add features", commits.get(1).getFullMessage());
        }
    }

    @Test
    public void testExtractFirstCommit() {
        // extract commit1 → should get only commit1
        try (RepositoryAccess result = extract(source.commit1.name())) {
            final List<RevCommit> commits = result.collectCommits("refs/heads/main");
            assertEquals(1, commits.size());
            assertEquals("initial", commits.get(0).getFullMessage());
            assertEquals(0, commits.get(0).getParentCount());
        }
    }

    @Test
    public void testExtractedContentPreserved() {
        try (RepositoryAccess result = extract(source.commit3.name())) {
            final List<RevCommit> commits = result.collectCommits("refs/heads/main");
            final RevCommit latest = commits.get(commits.size() - 1);

            // should have README.md + com/
            final List<Entry> root = result.readTree(latest.getTree().getId(), null);
            assertTrue(root.stream().anyMatch(e -> e.getName().equals("README.md")));
            assertTrue(root.stream().anyMatch(e -> e.getName().equals("com")));
        }
    }

    @Test
    public void testExtractedRefsAreMainAndHead() {
        try (RepositoryAccess result = extract(source.commit3.name())) {
            assertNotNull(result.getRef("refs/heads/main"));
            // tags should not be carried over
            assertNull(result.getRef("refs/tags/v1.0"));
        }
    }
}
