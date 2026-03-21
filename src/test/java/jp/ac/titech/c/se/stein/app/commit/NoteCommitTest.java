package jp.ac.titech.c.se.stein.app.commit;

import jp.ac.titech.c.se.stein.app.blob.HistorageViaJDT;
import jp.ac.titech.c.se.stein.app.blob.TokenizeViaJDT;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class NoteCommitTest {
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
    public void testNoTransform() {
        // NoteCommit directly on source: no prior notes → zero id prefix
        try (TestRepo.RewriteResult result = source.rewrite(new NoteCommit())) {
            final List<RevCommit> commits = result.access.collectCommits("refs/heads/main");
            assertEquals(3, commits.size());

            final String zeroId = ObjectId.zeroId().name();
            for (RevCommit commit : commits) {
                assertTrue(commit.getFullMessage().startsWith(zeroId + " "));
            }
            assertEquals(zeroId + " initial", commits.get(0).getFullMessage());
            assertEquals(zeroId + " add features", commits.get(1).getFullMessage());
            assertEquals(zeroId + " modern syntax", commits.get(2).getFullMessage());
        }
    }

    @Test
    public void testSingleTransform() {
        // Tokenize → NoteCommit: notes contain original commit IDs
        final List<RevCommit> sourceCommits = source.access.collectCommits("refs/heads/main");

        try (TestRepo.RewriteResult tokenized = source.rewrite(new TokenizeViaJDT());
             TestRepo.RewriteResult noted = tokenized.rewrite(new NoteCommit())) {

            final List<RevCommit> commits = noted.access.collectCommits("refs/heads/main");
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
        final List<RevCommit> sourceCommits = source.access.collectCommits("refs/heads/main");

        try (TestRepo.RewriteResult step1 = source.rewrite(new HistorageViaJDT());
             TestRepo.RewriteResult step2 = step1.rewrite(new TokenizeViaJDT());
             TestRepo.RewriteResult noted = step2.rewrite(new NoteCommit())) {

            final List<RevCommit> commits = noted.access.collectCommits("refs/heads/main");
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
