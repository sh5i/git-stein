package jp.ac.titech.c.se.stein.testing;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RefEntry;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.entry.Entry;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.*;

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

    public final InMemoryRepository repo = new InMemoryRepository(new DfsRepositoryDescription("test-repo"));
    public final RepositoryAccess access = new RepositoryAccess(repo);
    public ObjectId commit1, commit2, commit3;

    private TestRepo() {}

    /**
     * Creates and populates the test repository.
     */
    public static TestRepo create() throws IOException {
        final TestRepo testRepo = new TestRepo();
        testRepo.populate();
        return testRepo;
    }

    @Override
    public void close() {
        repo.close();
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

            inserter.flush();
        }

        // refs
        access.applyRefUpdate(new RefEntry("refs/heads/main", commit3));
        access.applyRefUpdate(new RefEntry("HEAD", "refs/heads/main"));

        // tag v1.0
        try (final ObjectInserter inserter = repo.newObjectInserter()) {
            final Context c = Context.init().with(Context.Key.inserter, inserter);
            access.writeTag(commit3, Constants.OBJ_COMMIT, "v1.0",
                    withTime(AUTHOR, DATE3), "release v1.0", c);
            inserter.flush();
        }
        access.applyRefUpdate(new RefEntry("refs/tags/v1.0", commit3));
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
