package jp.ac.titech.c.se.stein.testing;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RefEntry;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import jp.ac.titech.c.se.stein.util.TemporaryFile;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Builds an in-memory Git repository from test resources for use in tests.
 *
 * <p>The repository contains a 3-commit history of a small Java project
 * with the tree structure {@code com/example/Hello.java} and {@code README.md}:</p>
 * <ul>
 *   <li>commit 1 ({@code "initial"}): basic class structure + README.md</li>
 *   <li>commit 2 ({@code "add features"}): methods, inner class, default method</li>
 *   <li>commit 3 ({@code "modern syntax"}): lambda, switch expression, record, sealed interface</li>
 * </ul>
 *
 * <p>The {@code main} branch points to commit 3, and tag {@code v1.0} is attached to it.</p>
 */
public class TestRepo implements AutoCloseable {
    private static final long DATE1 = LocalDateTime.of(2024, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC);
    private static final long DATE2 = LocalDateTime.of(2024, 2, 1, 0, 0).toEpochSecond(ZoneOffset.UTC);
    private static final long DATE3 = LocalDateTime.of(2024, 3, 1, 0, 0).toEpochSecond(ZoneOffset.UTC);

    private static final PersonIdent AUTHOR = new PersonIdent("Test Author", "author@example.com", DATE1 * 1000, 0);
    private static final PersonIdent COMMITTER = new PersonIdent("Test Committer", "committer@example.com", DATE1 * 1000, 0);
    private static final int BLOB_MODE = FileMode.REGULAR_FILE.getBits();
    private static final int TREE_MODE = FileMode.TREE.getBits();

    public final Repository repo;
    public final RepositoryAccess access;
    public ObjectId commit1, commit2, commit3;

    private TemporaryFile.Directory tmpDir; // non-null for on-disk repos

    private TestRepo(Repository repo) {
        this.repo = repo;
        this.access = new RepositoryAccess(repo);
    }

    /**
     * Creates and populates an in-memory test repository.
     */
    public static TestRepo create() throws IOException {
        final TestRepo testRepo = new TestRepo(new InMemoryRepository(new DfsRepositoryDescription("test-repo")));
        testRepo.populate();
        return testRepo;
    }

    /**
     * Creates and populates a file-based test repository in a temporary directory.
     */
    public static TestRepo createOnDisk() throws IOException {
        final TemporaryFile.Directory dir = TemporaryFile.directoryOf("test-repo-");
        final Repository repo = new FileRepositoryBuilder()
                .setGitDir(new File(dir.getPath().toFile(), ".git"))
                .setWorkTree(dir.getPath().toFile())
                .build();
        repo.create();
        final TestRepo testRepo = new TestRepo(repo);
        testRepo.tmpDir = dir;
        testRepo.populate();
        return testRepo;
    }

    /**
     * Runs the given blob translator against this repository and returns a {@link RewriteResult}.
     */
    public RewriteResult rewrite(BlobTranslator translator) {
        return rewrite(translator.create());
    }

    /**
     * Runs the given rewriter against this repository and returns a {@link RewriteResult}.
     */
    public RewriteResult rewrite(RepositoryRewriter rewriter) {
        final Repository targetRepo = new InMemoryRepository(new DfsRepositoryDescription("target"));
        rewriter.setConfig(new Application.Config());
        rewriter.initialize(repo, targetRepo);
        rewriter.rewrite(Context.init());
        return new RewriteResult(targetRepo);
    }

    /**
     * Runs the given rewriter using a file-based target repository.
     * The target is created in a temporary directory and cleaned up on close.
     */
    public RewriteResult rewriteOnDisk(RepositoryRewriter rewriter) throws IOException {
        return rewriteOnDisk(rewriter, false);
    }

    /**
     * Runs the given rewriter using a file-based target repository.
     * If {@code useAlternates} is true, alternates are set up so the target shares
     * objects from this repository.
     */
    public RewriteResult rewriteOnDisk(RepositoryRewriter rewriter, boolean useAlternates) throws IOException {
        final TemporaryFile.Directory dir = TemporaryFile.directoryOf("test-target-");
        Repository targetRepo = new FileRepositoryBuilder()
                .setGitDir(new File(dir.getPath().toFile(), ".git"))
                .setWorkTree(dir.getPath().toFile())
                .build();
        targetRepo.create();

        if (useAlternates) {
            new RepositoryAccess(targetRepo).setupAlternates(repo, true);
            targetRepo.close();
            targetRepo = new FileRepositoryBuilder()
                    .setGitDir(new File(dir.getPath().toFile(), ".git"))
                    .setWorkTree(dir.getPath().toFile())
                    .build();
        }

        rewriter.setConfig(new Application.Config());
        rewriter.initialize(repo, targetRepo);
        rewriter.rewrite(Context.init());
        return new RewriteResult(targetRepo, dir);
    }



    /**
     * The result of a rewrite operation, holding the target repository.
     */
    public static class RewriteResult implements AutoCloseable {
        public final Repository repo;
        public final RepositoryAccess access;
        private TemporaryFile.Directory tmpDir;

        public RewriteResult(Repository repo) {
            this.repo = repo;
            this.access = new RepositoryAccess(repo);
        }

        RewriteResult(Repository repo, TemporaryFile.Directory tmpDir) {
            this(repo);
            this.tmpDir = tmpDir;
        }

        /**
         * Runs a further rewrite on this result's repository.
         */
        public RewriteResult rewrite(RepositoryRewriter rewriter) {
            final Repository targetRepo = new InMemoryRepository(new DfsRepositoryDescription("target"));
            rewriter.setConfig(new Application.Config());
            rewriter.initialize(repo, targetRepo);
            rewriter.rewrite(Context.init());
            return new RewriteResult(targetRepo);
        }

        /**
         * Runs a further rewrite on this result's repository using a blob translator.
         */
        public RewriteResult rewrite(BlobTranslator translator) {
            return rewrite(translator.create());
        }

        @Override
        public void close() {
            repo.close();
            if (tmpDir != null) {
                try { tmpDir.close(); } catch (IOException e) { /* ignore */ }
            }
        }
    }

    @Override
    public void close() {
        repo.close();
        if (tmpDir != null) {
            try { tmpDir.close(); } catch (IOException e) { /* ignore */ }
        }
    }

    private void populate() throws IOException {
        try (final ObjectInserter inserter = repo.newObjectInserter()) {
            final Context c = Context.init().with(Context.Key.inserter, inserter);

            // commit 1: initial
            final ObjectId tree1 = buildRootTree(c, "v1");
            commit1 = access.writeCommit(RepositoryAccess.NO_PARENTS, tree1,
                    withTime(AUTHOR, DATE1), withTime(COMMITTER, DATE1),
                    "initial", c);

            // commit 2: add features
            final ObjectId tree2 = buildRootTree(c, "v2");
            commit2 = access.writeCommit(new ObjectId[]{commit1}, tree2,
                    withTime(AUTHOR, DATE2), withTime(COMMITTER, DATE2),
                    "add features", c);

            // commit 3: modern syntax
            final ObjectId tree3 = buildRootTree(c, "v3");
            commit3 = access.writeCommit(new ObjectId[]{commit2}, tree3,
                    withTime(AUTHOR, DATE3), withTime(COMMITTER, DATE3),
                    "modern syntax", c);

            // tag v1.0 (annotated)
            final ObjectId tagId = access.writeTag(commit3, Constants.OBJ_COMMIT, "v1.0",
                    withTime(AUTHOR, DATE3), "release v1.0", c);

            inserter.flush();

            // refs
            access.applyRefUpdate(new RefEntry("refs/heads/main", commit3));
            access.applyRefUpdate(new RefEntry("HEAD", "refs/heads/main"));
            access.applyRefUpdate(new RefEntry("refs/tags/v1.0", tagId));
        }
    }

    /**
     * Builds the root tree: README.md + com/example/Hello.java
     */
    private ObjectId buildRootTree(Context c, String version) throws IOException {
        final ObjectId readmeBlob = access.writeBlob(readResource("/sample/README.md"), c);
        final ObjectId helloBlob = access.writeBlob(readResource("/sample/Hello.java." + version), c);

        // com/example/Hello.java
        final ObjectId exampleTree = access.writeTree(List.of(
                Entry.of(BLOB_MODE, "Hello.java", helloBlob)), c);
        final ObjectId comTree = access.writeTree(List.of(
                Entry.of(TREE_MODE, "example", exampleTree)), c);

        // root: README.md + com/
        return access.writeTree(List.of(
                Entry.of(BLOB_MODE, "README.md", readmeBlob),
                Entry.of(TREE_MODE, "com", comTree)), c);
    }

    private static byte[] readResource(String path) throws IOException {
        try (InputStream is = TestRepo.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            return is.readAllBytes();
        }
    }

    private static PersonIdent withTime(PersonIdent base, long epochSeconds) {
        return new PersonIdent(base.getName(), base.getEmailAddress(), epochSeconds * 1000, 0);
    }
}
