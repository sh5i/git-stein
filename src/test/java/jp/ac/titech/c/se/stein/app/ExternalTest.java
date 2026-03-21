package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExternalTest {
    static TestRepo source;

    @BeforeAll
    static void setUp() throws IOException {
        source = TestRepo.create();
    }

    @AfterAll
    static void tearDown() {
        source.close();
    }

    @Test
    public void testCreateWithIdentity() {
        final External external = new External();
        external.klass = Identity.class;
        external.args = null;

        final RepositoryRewriter rewriter = external.create();
        assertInstanceOf(Identity.class, rewriter);
    }

    @Test
    public void testRewriteWithIdentity() {
        final External external = new External();
        external.klass = Identity.class;
        external.args = null;

        try (TestRepo.RewriteResult result = source.rewrite(external.create())) {
            final List<RevCommit> sourceCommits = source.access.collectCommits("refs/heads/main");
            final List<RevCommit> targetCommits = result.access.collectCommits("refs/heads/main");

            assertEquals(sourceCommits.size(), targetCommits.size());
            for (int i = 0; i < sourceCommits.size(); i++) {
                assertEquals(sourceCommits.get(i).getId(), targetCommits.get(i).getId());
            }
        }
    }
}
