package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.util.SizeConverter;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class FilterBlobTest {
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
    public void testSizeConverter() {
        final SizeConverter converter = new SizeConverter();
        assertEquals(Long.valueOf(10), converter.convert("10"));
        assertEquals(Long.valueOf(10), converter.convert("10B"));
        assertEquals(Long.valueOf(1024), converter.convert("1K"));
        assertEquals(Long.valueOf(2048), converter.convert("2k"));
        assertEquals(Long.valueOf(3 * 1024 * 1024), converter.convert("3M"));
        assertEquals(Long.valueOf(1024 * 1024 * 1024), converter.convert("1G"));
        assertEquals(Long.valueOf(1536), converter.convert("1.5K"));
        assertThrows(IllegalArgumentException.class, () -> converter.convert(""));
    }

    @Test
    public void testFilterBySize() throws Exception {
        // maxSize=100: README.md (27B) passes, Hello.java (~1KB) is removed
        final FilterBlob filter = new FilterBlob();
        filter.maxSize = 100;

        try (RepositoryAccess result = TestRepo.rewrite(source,filter)) {
            final List<RevCommit> commits = result.collectCommits("refs/heads/main");
            assertEquals(3, commits.size());
            for (RevCommit commit : commits) {
                final List<Entry> root = result.readTree(commit.getTree().getId(), null);
                assertTrue(root.stream().anyMatch(e -> e.getName().equals("README.md")),
                        "README.md should remain in commit: " + commit.getFullMessage());
                assertFalse(root.stream().anyMatch(e -> e.getName().equals("com")),
                        "com/ should be removed in commit: " + commit.getFullMessage());
            }
        }
    }

    @Test
    public void testFilterByName() throws Exception {
        // pattern=*.java with invert: removes .java files, keeps README.md
        final FilterBlob filter = new FilterBlob();
        filter.nameFilter.setPatterns("*.java");
        filter.nameFilter.setInvertMatch(true);

        try (RepositoryAccess result = TestRepo.rewrite(source,filter)) {
            final List<RevCommit> commits = result.collectCommits("refs/heads/main");
            assertEquals(3, commits.size());
            for (RevCommit commit : commits) {
                final List<Entry> root = result.readTree(commit.getTree().getId(), null);
                assertTrue(root.stream().anyMatch(e -> e.getName().equals("README.md")),
                        "README.md should remain in commit: " + commit.getFullMessage());
                assertFalse(root.stream().anyMatch(e -> e.getName().equals("com")),
                        "com/ should be removed in commit: " + commit.getFullMessage());
            }
        }
    }

}
