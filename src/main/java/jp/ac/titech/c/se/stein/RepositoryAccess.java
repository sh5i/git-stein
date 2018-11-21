package jp.ac.titech.c.se.stein;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.treewalk.TreeWalk;

import jp.ac.titech.c.se.stein.Entry.SingleEntry;
import jp.ac.titech.c.se.stein.Try.ThrowableFunction;

public class RepositoryAccess {

    protected Repository repo;

    protected Repository writeRepo;

    public RepositoryAccess() {
    }

    public void initialize(final Repository repo) {
        this.repo = repo;
        this.writeRepo = repo;
    }

    public void initialize(final Repository readRepo, final Repository writeRepo) {
        this.repo = readRepo;
        this.writeRepo = writeRepo;
    }

    /**
     * Specifies the commit that the given ref indicates.
     */
    protected ObjectId specifyCommit(final Ref ref) {
        final Ref peeled = Try.io(() -> repo.getRefDatabase().peel(ref));
        return peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : ref.getObjectId();
    }

    /**
     * Reads a tree object.
     */
    protected List<SingleEntry> readTree(final ObjectId treeId, final String path) {
        final List<SingleEntry> result = new ArrayList<>();
        Try.io(() -> {
            try (final TreeWalk walk = new TreeWalk(repo)) {
                walk.addTree(treeId);
                walk.setRecursive(false);
                while (walk.next()) {
                    result.add(Entry.of(walk.getFileMode(), walk.getNameString(), walk.getObjectId(0), path));
                }
            }
        });
        return result;
    }

    /**
     * Write tree entries to a tree object.
     */
    protected ObjectId writeTree(final Collection<SingleEntry> entries) {
        final TreeFormatter f = new TreeFormatter();
        for (final SingleEntry e : entries) {
            f.append(e.name, e.mode, e.id);
        }
        return tryInsert((ins) -> ins.insert(f));
    }

    /**
     * Reads a blob object.
     */
    protected byte[] readBlob(final ObjectId blobId) {
        return Try.io(() -> repo.getObjectDatabase().open(blobId, Constants.OBJ_BLOB).getBytes());
    }

    /**
     * Writes data to a blob object.
     */
    public ObjectId writeBlob(final byte[] data) {
        return tryInsert((ins) -> ins.insert(Constants.OBJ_BLOB, data));
    }

    /**
     * Prepares an object inserter.
     */
    protected <R> R tryInsert(final ThrowableFunction<ObjectInserter, R> f) {
        try (final ObjectInserter inserter = writeRepo.newObjectInserter()) {
            return Try.io(f).apply(inserter);
        }
    }
}
