package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ExternalTest {
    static RepositoryAccess source;

    @BeforeAll
    static void setUp() throws IOException {
        source = TestRepo.createSample();
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

        try (RepositoryAccess result = TestRepo.rewrite(source,external.create())) {
            final List<RevCommit> sourceCommits = source.collectCommits("refs/heads/main");
            final List<RevCommit> targetCommits = result.collectCommits("refs/heads/main");

            assertEquals(sourceCommits.size(), targetCommits.size());
            for (int i = 0; i < sourceCommits.size(); i++) {
                assertEquals(sourceCommits.get(i).getId(), targetCommits.get(i).getId());
            }
        }
    }
}
