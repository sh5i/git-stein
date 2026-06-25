package jp.ac.titech.c.se.stein;

import jp.ac.titech.c.se.stein.app.Identity;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
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
        try (RepositoryAccess without = TestRepo.rewrite(source, new Identity());
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

    @Test
    public void testNamespacePipeline() throws Exception {
        final File targetDir = freshTargetDir();
        runIdentityPipeline(targetDir);

        final ObjectId sourceMain = source.repo.resolve("refs/heads/main");
        try (FileRepository repo = open(targetDir)) {
            // identity x identity preserves the ids end to end into the root namespace
            assertEquals(sourceMain, repo.resolve("refs/heads/main"));
            // the single intermediate version is left in place under its namespace (keep mode)
            assertEquals(sourceMain, repo.getRefDatabase()
                    .exactRef("refs/namespaces/git-stein.1/refs/heads/main").getObjectId());
        }

        FileUtils.deleteDirectory(targetDir);
    }

    @Test
    public void testObsoleteStagingRefsArePruned() throws Exception {
        final File targetDir = freshTargetDir();
        runIdentityPipeline(targetDir);

        // Inject an obsolete staging head, as if left over from a previous, differently-shaped run.
        // Being a branch it is in scope, so without pruning it would leak into the next run's output.
        try (FileRepository repo = open(targetDir)) {
            final ObjectId real = repo.resolve("refs/namespaces/git-stein.1/refs/heads/main");
            final RefUpdate u = repo.getRefDatabase().newUpdate("refs/namespaces/git-stein.1/refs/heads/ghost", false);
            u.setNewObjectId(real);
            u.setForceUpdate(true);
            u.update();
        }

        runIdentityPipeline(targetDir);

        final ObjectId sourceMain = source.repo.resolve("refs/heads/main");
        try (FileRepository repo = open(targetDir)) {
            // stage 0 rewrites the staging namespace authoritatively, so the obsolete head is pruned
            assertNull(repo.getRefDatabase().exactRef("refs/namespaces/git-stein.1/refs/heads/ghost"));
            // and it never reaches the final output
            assertNull(repo.resolve("refs/heads/ghost"));
            assertEquals(sourceMain, repo.resolve("refs/heads/main"));
        }

        FileUtils.deleteDirectory(targetDir);
    }

    @Test
    public void testInPlaceBackup() throws Exception {
        try (RepositoryAccess repo = TestRepo.createSample(true)) {
            final ObjectId origMain = repo.repo.resolve("refs/heads/main");

            final Application app = new Application();
            app.conf.source = repo.repo.getWorkTree();   // no --output => in-place
            app.conf.isAddingNotes = false;              // avoid the unrelated in-place + notes NPE
            app.rewriters.add(new Identity());
            app.call();

            // the original branch is preserved under the backup namespace
            assertEquals(origMain, repo.repo.getRefDatabase()
                    .exactRef("refs/namespaces/git-stein.original/refs/heads/main").getObjectId());
        }
    }

    private File freshTargetDir() throws IOException {
        final File dir = Files.createTempDirectory("git-stein-pipeline").toFile();
        Files.delete(dir.toPath());
        return dir;
    }

    private void runIdentityPipeline(final File targetDir) throws Exception {
        final Application app = new Application();
        app.conf.source = source.repo.getWorkTree();
        app.conf.output = new Application.Config.OutputOptions();
        app.conf.output.target = targetDir;
        app.conf.isAddingNotes = false;
        app.rewriters.add(new Identity());
        app.rewriters.add(new Identity());
        app.call();
    }

    private static FileRepository open(final File dir) throws IOException {
        return (FileRepository) new FileRepositoryBuilder()
                .setWorkTree(dir).setGitDir(new File(dir, ".git")).build();
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
