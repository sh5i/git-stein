package jp.ac.titech.c.se.stein.testing;

import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.entry.Entry;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestRepoTest {
    static RepositoryAccess testRepo;
    static List<RevCommit> commits;

    @BeforeAll
    static void setUp() throws IOException {
        testRepo = TestRepo.createSample();
        commits = testRepo.collectCommits("refs/heads/main");
    }

    @AfterAll
    static void tearDown() {
        testRepo.close();
    }

    @Test
    public void testCommitHistory() {
        assertEquals(3, commits.size());
        assertNotEquals(commits.get(0).getId(), commits.get(1).getId());
        assertNotEquals(commits.get(1).getId(), commits.get(2).getId());
    }

    @Test
    public void testWalk() {
        assertEquals("initial", commits.get(0).getFullMessage());
        assertEquals("add features", commits.get(1).getFullMessage());
        assertEquals("modern syntax", commits.get(2).getFullMessage());

        // parent chain
        assertEquals(0, commits.get(0).getParentCount());
        assertEquals(commits.get(0).getId(), commits.get(1).getParent(0).getId());
        assertEquals(commits.get(1).getId(), commits.get(2).getParent(0).getId());
    }

    @Test
    public void testRefs() {
        final Ref main = testRepo.getRef("refs/heads/main");
        assertNotNull(main);
        assertEquals(commits.get(2).getId(), main.getObjectId());

        assertNotNull(testRepo.getRef("refs/tags/v1.0"));
    }

    @Test
    public void testTreeStructure() {
        final List<Entry> root = readTree(commits.get(0));
        assertEquals(2, root.size());
        assertTrue(root.stream().anyMatch(e -> e.getName().equals("README.md") && e.isBlob()));
        assertTrue(root.stream().anyMatch(e -> e.getName().equals("com") && e.isTree()));

        // com/example/Hello.java
        final Entry comDir = root.stream().filter(e -> e.getName().equals("com")).findFirst().orElseThrow();
        final List<Entry> comEntries = testRepo.readTree(comDir.getId(), "com");
        assertEquals(1, comEntries.size());
        assertEquals("example", comEntries.get(0).getName());

        final List<Entry> exampleEntries = testRepo.readTree(comEntries.get(0).getId(), "com/example");
        assertEquals(1, exampleEntries.size());
        assertEquals("Hello.java", exampleEntries.get(0).getName());
    }

    @Test
    public void testBlobContent() {
        final List<Entry> root = readTree(commits.get(0));
        final Entry readme = root.stream()
                .filter(e -> e.getName().equals("README.md"))
                .findFirst().orElseThrow();
        assertTrue(new String(testRepo.readBlob(readme.getId())).contains("# Hello"));
    }

    @Test
    public void testHelloJavaEvolution() {
        final String v1 = readHelloJava(commits.get(0));
        assertFalse(v1.contains("import "));

        final String v2 = readHelloJava(commits.get(1));
        assertTrue(v2.contains("import java.util.List"));
        assertTrue(v2.contains("greetMany"));

        final String v3 = readHelloJava(commits.get(2));
        assertTrue(v3.contains("sealed interface"));
        assertTrue(v3.contains("record Pair"));
        assertTrue(v3.contains("switch (color)"));
    }

    private List<Entry> readTree(RevCommit commit) {
        return testRepo.readTree(commit.getTree().getId(), null);
    }

    private String readHelloJava(RevCommit commit) {
        final List<Entry> root = readTree(commit);
        final Entry com = root.stream().filter(e -> e.getName().equals("com")).findFirst().orElseThrow();
        final Entry example = testRepo.readTree(com.getId(), "com").get(0);
        final Entry hello = testRepo.readTree(example.getId(), "com/example").get(0);
        return new String(testRepo.readBlob(hello.getId()));
    }
}
