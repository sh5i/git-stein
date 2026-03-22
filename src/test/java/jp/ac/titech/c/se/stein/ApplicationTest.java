package jp.ac.titech.c.se.stein;

import jp.ac.titech.c.se.stein.app.Identity;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for CLI features, using file-based repositories.
 */
public class ApplicationTest {
    static RepositoryAccess source;

    @BeforeAll
    static void setUp() throws IOException {
        source = TestRepo.createSample(true);
    }

    @AfterAll
    static void tearDown() {
        source.close();
    }

    @Test
    public void testAlternates() throws Exception {
        try (RepositoryAccess without = TestRepo.rewrite(source, TestRepo.create(true), new Identity());
             RepositoryAccess with = TestRepo.rewrite(source, TestRepo.create(true).setupAlternates(source.repo, true), new Identity())) {

            // same commit IDs
            final List<RevCommit> commitsWithout = without.collectCommits("refs/heads/main");
            final List<RevCommit> commitsWith = with.collectCommits("refs/heads/main");
            assertEquals(commitsWithout.size(), commitsWith.size());
            for (int i = 0; i < commitsWithout.size(); i++) {
                assertEquals(commitsWithout.get(i).getId(), commitsWith.get(i).getId());
            }

            // alternates file exists with relative path
            final File alternatesFile = new File(with.repo.getDirectory(), "objects/info/alternates");
            assertTrue(alternatesFile.exists());
            assertFalse(Files.readString(alternatesFile.toPath()).trim().startsWith("/"));

            // target with alternates has fewer local objects
            final long sizeWithout = dirSize(new File(without.repo.getDirectory(), "objects"));
            final long sizeWith = dirSize(new File(with.repo.getDirectory(), "objects"));
            assertTrue(sizeWith < sizeWithout,
                    "Expected fewer local objects with alternates: with=" + sizeWith + ", without=" + sizeWithout);
        }
    }

    static long dirSize(File dir) {
        long size = 0;
        final File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                size += f.isFile() ? f.length() : dirSize(f);
            }
        }
        return size;
    }
}
