package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.Context;
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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class ClusterTest {
    static TestRepo source;

    @BeforeAll
    static void setUp() throws IOException {
        source = TestRepo.create();
    }

    @AfterAll
    static void tearDown() {
        source.close();
    }

    TemporaryRepositoryAccess clusterWith(String recipeJson) throws IOException {
        final Path recipeFile = Files.createTempFile("recipe", ".json");
        Files.writeString(recipeFile, recipeJson);

        try {
            final Cluster cluster = new Cluster();
            cluster.recipeFile = recipeFile.toFile();
            cluster.setConfig(new Application.Config());

            final Repository targetRepo = new InMemoryRepository(new DfsRepositoryDescription("target"));
            cluster.initialize(source.access.repo, targetRepo);
            cluster.rewrite(Context.init());
            return new TemporaryRepositoryAccess(targetRepo);
        } finally {
            Files.deleteIfExists(recipeFile);
        }
    }

    @Test
    public void testNoOpRecipe() throws IOException {
        // empty recipe: no changes
        try (RepositoryAccess result = clusterWith("{}")) {
            final List<RevCommit> commits = result.collectCommits("refs/heads/main");
            assertEquals(3, commits.size());
            assertEquals("initial", commits.get(0).getFullMessage());
            assertEquals("add features", commits.get(1).getFullMessage());
            assertEquals("modern syntax", commits.get(2).getFullMessage());
        }
    }

    @Test
    public void testForcedClusterMergesTwoCommits() throws IOException {
        // force merge commit2 into commit1 (they are parent-child, so safe merge would refuse)
        final String recipe = String.format(
                "{\"forcedClusters\": [[\"%s\", \"%s\"]]}",
                source.commit1.name(), source.commit2.name());

        try (RepositoryAccess result = clusterWith(recipe)) {
            final List<RevCommit> commits = result.collectCommits("refs/heads/main");
            assertEquals(2, commits.size());
        }
    }

    @Test
    public void testForcedClusterMergesAllCommits() throws IOException {
        // force merge all three into commit1
        final String recipe = String.format(
                "{\"forcedClusters\": [[\"%s\", \"%s\", \"%s\"]]}",
                source.commit1.name(), source.commit2.name(), source.commit3.name());

        try (RepositoryAccess result = clusterWith(recipe)) {
            final List<RevCommit> commits = result.collectCommits("refs/heads/main");
            assertEquals(1, commits.size());
        }
    }
}
