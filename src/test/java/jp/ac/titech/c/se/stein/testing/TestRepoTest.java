package jp.ac.titech.c.se.stein.testing;

import jp.ac.titech.c.se.stein.core.Try;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.jgit.RevWalk;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TestRepoTest {
    static TestRepo testRepo;

    @BeforeAll
    static void setUp() throws IOException {
        testRepo = TestRepo.create();
    }

    @AfterAll
    static void tearDown() {
        testRepo.close();
    }

    @Test
    public void testCommitHistory() {
        assertNotNull(testRepo.commit1);
        assertNotNull(testRepo.commit2);
        assertNotNull(testRepo.commit3);
        assertNotEquals(testRepo.commit1, testRepo.commit2);
        assertNotEquals(testRepo.commit2, testRepo.commit3);
    }

    @Test
    public void testWalk() {
        final List<RevCommit> commits = new ArrayList<>();
        try (RevWalk walk = testRepo.access.walk()) {
            Try.io(() -> walk.memoMarkStart(walk.parseCommit(testRepo.commit3)));
            walk.forEach(commits::add);
        }
        assertEquals(3, commits.size());

        // reverse topological order: oldest first
        assertEquals(testRepo.commit1, commits.get(0).getId());
        assertEquals(testRepo.commit2, commits.get(1).getId());
        assertEquals(testRepo.commit3, commits.get(2).getId());

        assertEquals("initial", commits.get(0).getFullMessage());
        assertEquals("add features", commits.get(1).getFullMessage());
        assertEquals("modern syntax", commits.get(2).getFullMessage());

        // parent chain
        assertEquals(0, commits.get(0).getParentCount());
        assertEquals(testRepo.commit1, commits.get(1).getParent(0).getId());
        assertEquals(testRepo.commit2, commits.get(2).getParent(0).getId());
    }

    @Test
    public void testRefs() {
        final Ref main = testRepo.access.getRef("refs/heads/main");
        assertNotNull(main);
        assertEquals(testRepo.commit3, main.getObjectId());

        assertNotNull(testRepo.access.getRef("refs/tags/v1.0"));
    }

    @Test
    public void testTreeStructure() {
        final List<Entry> root = readTree(testRepo.commit1, null);
        assertEquals(2, root.size());
        assertTrue(root.stream().anyMatch(e -> e.getName().equals("README.md") && e.isBlob()));
        assertTrue(root.stream().anyMatch(e -> e.getName().equals("com") && e.isTree()));

        // com/example/Hello.java
        final Entry comDir = root.stream().filter(e -> e.getName().equals("com")).findFirst().orElseThrow();
        final List<Entry> comEntries = testRepo.access.readTree(comDir.getId(), "com");
        assertEquals(1, comEntries.size());
        assertEquals("example", comEntries.get(0).getName());

        final List<Entry> exampleEntries = testRepo.access.readTree(comEntries.get(0).getId(), "com/example");
        assertEquals(1, exampleEntries.size());
        assertEquals("Hello.java", exampleEntries.get(0).getName());
    }

    @Test
    public void testBlobContent() {
        final List<Entry> root = readTree(testRepo.commit1, null);
        final Entry readme = root.stream()
                .filter(e -> e.getName().equals("README.md"))
                .findFirst().orElseThrow();
        assertTrue(new String(testRepo.access.readBlob(readme.getId())).contains("# Hello"));
    }

    @Test
    public void testHelloJavaEvolution() {
        final String v1 = readHelloJava(testRepo.commit1);
        assertFalse(v1.contains("import "));

        final String v2 = readHelloJava(testRepo.commit2);
        assertTrue(v2.contains("import java.util.List"));
        assertTrue(v2.contains("greetMany"));

        final String v3 = readHelloJava(testRepo.commit3);
        assertTrue(v3.contains("sealed interface"));
        assertTrue(v3.contains("record Pair"));
        assertTrue(v3.contains("switch (color)"));
    }

    private List<Entry> readTree(ObjectId commitId, String path) {
        return testRepo.access.readTree(Try.io(() -> {
            try (RevWalk w = new RevWalk(testRepo.repo)) {
                return w.parseCommit(commitId).getTree().getId();
            }
        }), path);
    }

    private String readHelloJava(ObjectId commitId) {
        final List<Entry> root = readTree(commitId, null);
        final Entry com = root.stream().filter(e -> e.getName().equals("com")).findFirst().orElseThrow();
        final Entry example = testRepo.access.readTree(com.getId(), "com").get(0);
        final Entry hello = testRepo.access.readTree(example.getId(), "com/example").get(0);
        return new String(testRepo.access.readBlob(hello.getId()));
    }
}
