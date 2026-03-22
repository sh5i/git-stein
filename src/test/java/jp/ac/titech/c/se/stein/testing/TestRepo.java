package jp.ac.titech.c.se.stein.testing;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RefEntry;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import jp.ac.titech.c.se.stein.rewriter.RewriterCommand;
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
 * Factory for pre-populated test repositories and rewriting utilities.
 *
 * <p>The repository contains {@code com/example/Hello.java} and {@code README.md}:</p>
 * <ul>
 *   <li>commit 1 ({@code "initial"}): basic class structure + README.md</li>
 *   <li>commit 2 ({@code "add features"}): methods, inner class, default method</li>
 *   <li>commit 3 ({@code "modern syntax"}): lambda, switch expression, record, sealed interface</li>
 * </ul>
 *
 * <p>The {@code main} branch points to commit 3, and tag {@code v1.0} is attached to it.</p>
 */
public class TestRepo {
    private static final long DATE1 = LocalDateTime.of(2024, 1, 1, 0, 0).toEpochSecond(ZoneOffset.UTC);
    private static final long DATE2 = LocalDateTime.of(2024, 2, 1, 0, 0).toEpochSecond(ZoneOffset.UTC);
    private static final long DATE3 = LocalDateTime.of(2024, 3, 1, 0, 0).toEpochSecond(ZoneOffset.UTC);

    private static final PersonIdent AUTHOR = new PersonIdent("Test Author", "author@example.com", DATE1 * 1000, 0);
    private static final PersonIdent COMMITTER = new PersonIdent("Test Committer", "committer@example.com", DATE1 * 1000, 0);
    private static final int BLOB_MODE = FileMode.REGULAR_FILE.getBits();
    private static final int TREE_MODE = FileMode.TREE.getBits();

    private TestRepo() {}

    /**
     * Creates an empty in-memory repository.
     */
    public static TemporaryRepositoryAccess create() {
        return create(false);
    }

    /**
     * Creates an empty repository.
     * If {@code onDisk} is true, uses a file-based repository; otherwise in-memory.
     */
    public static TemporaryRepositoryAccess create(boolean onDisk) {
        try {
            final Repository repo;
            final TemporaryFile.Directory dir;
            if (onDisk) {
                dir = TemporaryFile.directoryOf("test-repo-");
                repo = new FileRepositoryBuilder()
                        .setGitDir(new File(dir.getPath().toFile(), ".git"))
                        .setWorkTree(dir.getPath().toFile())
                        .build();
                repo.create();
            } else {
                dir = null;
                repo = new InMemoryRepository(new DfsRepositoryDescription("test-repo"));
            }
            return new TemporaryRepositoryAccess(repo, dir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Creates and populates a sample test repository with a 3-commit history.
     */
    public static TemporaryRepositoryAccess createSample() throws IOException {
        return createSample(false);
    }

    /**
     * Creates and populates a sample test repository with a 3-commit history.
     * If {@code onDisk} is true, uses a file-based repository; otherwise in-memory.
     */
    public static TemporaryRepositoryAccess createSample(boolean onDisk) throws IOException {
        final TemporaryRepositoryAccess ra = create(onDisk);
        populate(ra);
        return ra;
    }

    /**
     * Runs the given command into a new target matching the source type (in-memory or on-disk).
     */
    public static TemporaryRepositoryAccess rewrite(RepositoryAccess source, RewriterCommand command) {
        return rewrite(source, create(isOnDisk(source)), command);
    }

    /**
     * Runs the given command from source into target and returns the target.
     */
    public static <T extends RepositoryAccess> T rewrite(RepositoryAccess source, T target, RewriterCommand cmd) {
        final RepositoryRewriter rewriter = cmd.toRewriter();
        rewriter.setConfig(new Application.Config());
        rewriter.initialize(source.repo, target.repo);
        rewriter.rewrite(Context.init());
        return target;
    }

    private static boolean isOnDisk(RepositoryAccess ra) {
        return !(ra.repo instanceof InMemoryRepository);
    }

    private static void populate(RepositoryAccess ra) throws IOException {
        try (final ObjectInserter inserter = ra.repo.newObjectInserter()) {
            final Context c = Context.init().with(Context.Key.inserter, inserter);

            final ObjectId tree1 = buildRootTree(ra, "v1", c);
            final ObjectId commit1 = ra.writeCommit(RepositoryAccess.NO_PARENTS, tree1,
                    withTime(AUTHOR, DATE1), withTime(COMMITTER, DATE1),
                    "initial", c);

            final ObjectId tree2 = buildRootTree(ra, "v2", c);
            final ObjectId commit2 = ra.writeCommit(new ObjectId[]{commit1}, tree2,
                    withTime(AUTHOR, DATE2), withTime(COMMITTER, DATE2),
                    "add features", c);

            final ObjectId tree3 = buildRootTree(ra, "v3", c);
            final ObjectId commit3 = ra.writeCommit(new ObjectId[]{commit2}, tree3,
                    withTime(AUTHOR, DATE3), withTime(COMMITTER, DATE3),
                    "modern syntax", c);

            final ObjectId tagId = ra.writeTag(commit3, Constants.OBJ_COMMIT, "v1.0",
                    withTime(AUTHOR, DATE3), "release v1.0", c);

            inserter.flush();

            ra.applyRefUpdate(new RefEntry("refs/heads/main", commit3));
            ra.applyRefUpdate(new RefEntry("HEAD", "refs/heads/main"));
            ra.applyRefUpdate(new RefEntry("refs/tags/v1.0", tagId));
        }
    }

    private static ObjectId buildRootTree(RepositoryAccess ra, String version, Context c) throws IOException {
        final ObjectId readmeBlob = ra.writeBlob(readResource("/sample/README.md"), c);
        final ObjectId helloBlob = ra.writeBlob(readResource("/sample/Hello.java." + version), c);

        final ObjectId exampleTree = ra.writeTree(List.of(
                Entry.of(BLOB_MODE, "Hello.java", helloBlob)), c);
        final ObjectId comTree = ra.writeTree(List.of(
                Entry.of(TREE_MODE, "example", exampleTree)), c);

        return ra.writeTree(List.of(
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
