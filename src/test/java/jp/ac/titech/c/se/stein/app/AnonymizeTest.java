package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class AnonymizeTest {
    static RepositoryAccess source, result;

    @BeforeAll
    static void setUp() throws IOException {
        source = TestRepo.createSample();
        final Anonymize anonymize = new Anonymize();
        anonymize.setAllEnabled(true);
        result = TestRepo.rewrite(source,anonymize);
    }

    @AfterAll
    static void tearDown() {
        result.close();
        source.close();
    }

    @Test
    public void testNameMap() {
        final Anonymize.NameMap map = new Anonymize.NameMap("file", "f");

        final String first = map.convert("hello");
        assertEquals(first, map.convert("hello"));
        assertNotEquals(first, map.convert("world"));
        assertTrue(first.startsWith("f"));
    }

    @Test
    public void testCommitMessages() {
        final List<RevCommit> commits = result.collectCommits("refs/heads/main");
        assertEquals(3, commits.size());
        assertEquals("8ad7d21", commits.get(0).getFullMessage());
        assertEquals("9c9dba5", commits.get(1).getFullMessage());
        assertEquals("9f4ffeb", commits.get(2).getFullMessage());
    }

    @Test
    public void testAuthorAnonymized() {
        final List<RevCommit> commits = result.collectCommits("refs/heads/main");
        for (RevCommit commit : commits) {
            assertEquals("p1", commit.getAuthorIdent().getName());
            assertEquals("e4f623b", commit.getAuthorIdent().getEmailAddress());
            assertEquals("p2", commit.getCommitterIdent().getName());
            assertEquals("cfacb20", commit.getCommitterIdent().getEmailAddress());
        }
    }

    @Test
    public void testBlobContentAnonymized() {
        final RevCommit latest = result.getHead("refs/heads/main");
        final List<Entry> files = result.flattenTree(latest.getTree().getId());
        for (Entry file : files) {
            if (file.isBlob()) {
                final String content = new String(result.readBlob(file.getId()));
                assertTrue(content.matches("[0-9a-f]{40}"),
                        "Expected hex hash content for " + file.getName() + ", got: " + content);
            }
        }
    }

    @Test
    public void testBlobNameAnonymized() {
        final RevCommit latest = result.getHead("refs/heads/main");
        final List<Entry> files = result.flattenTree(latest.getTree().getId());
        final List<String> blobNames = files.stream().filter(Entry::isBlob)
                .map(Entry::getName).sorted().collect(Collectors.toList());
        assertEquals(List.of("f1", "f2"), blobNames);
    }

    @Test
    public void testTreeNameAnonymized() {
        final RevCommit latest = result.getHead("refs/heads/main");
        final List<Entry> root = result.readTree(latest.getTree().getId(), null);
        final Entry tree = root.stream().filter(Entry::isTree).findFirst().orElseThrow();
        // com is renamed after example (depth-first), so com → t2
        assertEquals("t2", tree.getName());
    }

    @Test
    public void testMainBranchPreserved() {
        assertNotNull(result.getRef("refs/heads/main"));
    }

    @Test
    public void testTagNameAnonymized() {
        assertNull(result.getRef("refs/tags/v1.0"));
        assertNotNull(result.getRef("refs/tags/t1"));
    }

}
