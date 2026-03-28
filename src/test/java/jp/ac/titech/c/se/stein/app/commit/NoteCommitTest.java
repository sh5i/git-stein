package jp.ac.titech.c.se.stein.app.commit;

import jp.ac.titech.c.se.stein.app.blob.HistorageViaJDT;
import jp.ac.titech.c.se.stein.app.blob.TokenizeViaJDT;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class NoteCommitTest {
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
    public void testNoTransform() {
        // NoteCommit directly on source: no prior notes → commit's own id prefix
        final List<RevCommit> sourceCommits = source.collectCommits("refs/heads/main");

        try (RepositoryAccess result = TestRepo.rewrite(source, new NoteCommit())) {
            final List<RevCommit> commits = result.collectCommits("refs/heads/main");
            assertEquals(3, commits.size());

            for (int i = 0; i < 3; i++) {
                final String msg = commits.get(i).getFullMessage();
                final String expectedPrefix = sourceCommits.get(i).getId().name();
                assertTrue(msg.startsWith(expectedPrefix + " "),
                        "Expected source id " + expectedPrefix + " in: " + msg);
            }
        }
    }

    @Test
    public void testSingleTransform() {
        // Tokenize → NoteCommit: notes contain original commit IDs
        final List<RevCommit> sourceCommits = source.collectCommits("refs/heads/main");

        try (RepositoryAccess tokenized = TestRepo.rewrite(source,new TokenizeViaJDT());
             RepositoryAccess noted = TestRepo.rewrite(tokenized,new NoteCommit())) {

            final List<RevCommit> commits = noted.collectCommits("refs/heads/main");
            assertEquals(3, commits.size());

            for (int i = 0; i < 3; i++) {
                final String msg = commits.get(i).getFullMessage();
                final String expectedPrefix = sourceCommits.get(i).getId().name();
                assertTrue(msg.startsWith(expectedPrefix + " "),
                        "Expected original id " + expectedPrefix + " in: " + msg);
            }
        }
    }

    @Test
    public void testDoubleTransform() {
        // Historage → Tokenize → NoteCommit: notes should still trace back to original
        final List<RevCommit> sourceCommits = source.collectCommits("refs/heads/main");

        try (RepositoryAccess step1 = TestRepo.rewrite(source,new HistorageViaJDT());
             RepositoryAccess step2 = TestRepo.rewrite(step1,new TokenizeViaJDT());
             RepositoryAccess noted = TestRepo.rewrite(step2,new NoteCommit())) {

            final List<RevCommit> commits = noted.collectCommits("refs/heads/main");
            assertEquals(3, commits.size());

            for (int i = 0; i < 3; i++) {
                final String msg = commits.get(i).getFullMessage();
                final String expectedPrefix = sourceCommits.get(i).getId().name();
                assertTrue(msg.startsWith(expectedPrefix + " "),
                        "Expected original id " + expectedPrefix + " after double transform in: " + msg);
            }
        }
    }
}
