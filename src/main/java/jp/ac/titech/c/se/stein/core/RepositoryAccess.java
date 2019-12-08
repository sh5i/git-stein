package jp.ac.titech.c.se.stein.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.Consumer;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefRename;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.core.EntrySet.Entry;
import jp.ac.titech.c.se.stein.core.Try.IOThrowableFunction;

public class RepositoryAccess implements Configurable {
    private static final Logger log = LoggerFactory.getLogger(RepositoryAccess.class);

    public static final ObjectId[] NO_PARENTS = new ObjectId[0];

    protected Repository repo;

    protected Repository writeRepo;

    protected boolean overwrite = true;

    protected boolean dryRunning = false;

    public RepositoryAccess() {
    }

    public void setDryRunning(final boolean dryRunning) {
        this.dryRunning = dryRunning;
        log.debug("Dry running mode: {}", dryRunning);
    }

    @Override
    public void addOptions(final Config conf) {
        conf.addOption("n", "dry-run", false, "don't actually write anything");
    }

    @Override
    public void configure(final Config conf) {
        if (conf.hasOption("dry-run")) {
            setDryRunning(true);
        }
    }

    public void initialize(final Repository repo) {
        initialize(repo, repo);
    }

    public void initialize(final Repository readRepo, final Repository writeRepo) {
        this.repo = readRepo;
        this.writeRepo = writeRepo;
        this.overwrite = readRepo == writeRepo;
    }


    /**
     * Returns a RevWalk object.
     */
    protected RevWalk walk(final Context c) {
        final RevWalk walk = new RevWalk(repo);
        walk.sort(RevSort.TOPO, true);
        walk.sort(RevSort.REVERSE, true);
        return walk;
    }

    /**
     * Specifies the target object that the given ref indicates.
     */
    protected ObjectId getRefTarget(final Ref ref, final Context c) {
        final Ref peeled = Try.io(c, () -> repo.getRefDatabase().peel(ref));
        return peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : ref.getObjectId();
    }

    /**
     * Specifies the type of the given object.
     */
    protected int getObjectType(final ObjectId id, final Context c) {
        try (final RevWalk walk = new RevWalk(repo)) {
            final RevObject object = Try.io(c, () -> walk.parseAny(id));
            return object.getType();
        }
    }

    /**
     * Reads a tree object.
     */
    protected List<Entry> readTree(final ObjectId treeId, final String path, final Context c) {
        final List<Entry> result = new ArrayList<>();
        Try.io(c, () -> {
            try (final TreeWalk walk = new TreeWalk(repo)) {
                walk.addTree(treeId);
                walk.setRecursive(false);
                while (walk.next()) {
                    result.add(new Entry(walk.getFileMode(), walk.getNameString(), walk.getObjectId(0), path));
                }
            }
        });
        return result;
    }

    /**
     * Writes tree entries to a tree object.
     */
    protected ObjectId writeTree(final Collection<Entry> entries, final Context c) {
        final TreeFormatter f = new TreeFormatter();
        for (final Entry e : sortEntries(entries, c)) {
            f.append(e.name, e.mode, e.id);
        }
        return insert(ins -> dryRunning ? ins.idFor(f) : ins.insert(f), c);
    }

    /**
     * Sorts tree entries.
     */
    protected Collection<Entry> sortEntries(final Collection<Entry> entries, final Context c) {
        final SortedMap<String, Entry> map = new TreeMap<>();
        for (final Entry e : entries) {
            final String key = e.name + (e.isTree() ? "/" : "");
            if (map.containsKey(key)) {
                log.warn("Entry occurred twice: {} (found: {}, new: {}, {})", e.getPath(), map.get(key).id.name(), e.id.name(), c);
            }
            map.put(key, e);
        }
        return map.values();
    }

    /**
     * Reads a blob object.
     */
    protected byte[] readBlob(final ObjectId blobId, final Context c) {
        return Try.io(c, () -> repo.getObjectDatabase().open(blobId, Constants.OBJ_BLOB).getBytes());
    }

    /**
     * Writes data to a blob object.
     */
    public ObjectId writeBlob(final byte[] data, final Context c) {
        return insert(ins -> dryRunning ? ins.idFor(Constants.OBJ_BLOB, data) : ins.insert(Constants.OBJ_BLOB, data), c);
    }

    /**
     * Writes a commit object.
     */
    protected ObjectId writeCommit(final ObjectId[] parentIds, final ObjectId treeId, final PersonIdent author, final PersonIdent committer, final String message, final Context c) {
        final CommitBuilder builder = new CommitBuilder();
        builder.setParentIds(parentIds);
        builder.setTreeId(treeId);
        builder.setAuthor(author);
        builder.setCommitter(committer);
        builder.setMessage(message);
        return insert(ins -> dryRunning ? ins.idFor(Constants.OBJ_COMMIT, builder.build()) : ins.insert(builder), c);
    }

    /**
     * Writes notes.
     */
    protected void writeNotes(final NoteMap map, final Context c) {
        // TODO dry-running of map tree writing.
        final ObjectId treeId = insert(ins -> map.writeTree(ins), c);
        // TODO building PersonIdent better.
        final PersonIdent ident = new PersonIdent(repo);
        final String message = "Notes added by 'git notes add'";
        final ObjectId commit = writeCommit(NO_PARENTS, treeId, ident, ident, message, c);

        applyRefUpdate(new RefEntry(Constants.R_NOTES_COMMITS, commit), c);
    }

    /**
     * Writes a tag object.
     */
    protected ObjectId writeTag(final ObjectId objectId, final String tag, final PersonIdent tagger, final String message, final Context c) {
        final TagBuilder builder = new TagBuilder();
        builder.setObjectId(objectId, Constants.OBJ_COMMIT);
        builder.setTag(tag);
        builder.setTagger(tagger);
        builder.setMessage(message);
        return insert(ins -> dryRunning ? ins.idFor(Constants.OBJ_TAG, builder.build()) : ins.insert(builder), c);
    }

    /**
     * Retrieves all Ref objects.
     */
    protected List<Ref> getRefs(final Context c) {
        return Try.io(c, () -> repo.getRefDatabase().getRefs());
    }

    /**
     * Applies ref update.
     */
    protected void applyRefUpdate(final RefEntry entry, final Context c) {
        if (dryRunning) {
            return;
        }
        Try.io(c, () -> {
            final RefUpdate cmd = writeRepo.getRefDatabase().newUpdate(entry.name, false);
            cmd.setForceUpdate(true);
            if (entry.isSymbolic()) {
                cmd.link(entry.target);
            } else {
                cmd.setNewObjectId(entry.id);
                cmd.update();
            }
        });
    }

    /**
     * Tests whether the given ref indicates a tag.
     */
    protected boolean isTag(final Ref ref, final Context c) {
        final Ref peeled = Try.io(c, () -> repo.getRefDatabase().peel(ref));
        return peeled.getPeeledObjectId() != null;
    }

    /**
     * Extracts a rev object.
     */
    protected AnyObjectId parseAny(final ObjectId id, final Context c) {
        try (final RevWalk walk = new RevWalk(repo)) {
            return Try.io(c, () -> walk.parseAny(id));
        }
    }

    /**
     * Extracts a tag object.
     */
    protected RevTag parseTag(final ObjectId id, final Context c) {
        try (final RevWalk walk = new RevWalk(repo)) {
            return Try.io(c, () -> walk.parseTag(id));
        }
    }

    /**
     * Applies ref delete.
     */
    protected void applyRefDelete(final RefEntry entry, final Context c) {
        if (dryRunning) {
            return;
        }
        Try.io(c, () -> {
            final RefUpdate cmd = writeRepo.getRefDatabase().newUpdate(entry.name, false);
            cmd.setForceUpdate(true);
            cmd.delete();
        });
    }

    /**
     * Applies ref rename.
     */
    protected void applyRefRename(final String name, final String newName, final Context c) {
        if (dryRunning) {
            return;
        }
        Try.io(c, () -> {
            final RefRename cmd = writeRepo.getRefDatabase().newRename(name, newName);
            cmd.rename();
        });
    }

    /**
     * Opens and provides an object inserter.
     */
    protected void openInserter(final Consumer<ObjectInserter> f, final Context c) {
        try (final ObjectInserter ins = writeRepo.newObjectInserter()) {
            f.accept(ins);
        }
    }

    /**
     * Inserts objects using a prepared object inserter.
     */
    protected <R> R insert(final IOThrowableFunction<ObjectInserter, R> f, final Context c) {
        final ObjectInserter inserterContext = c.getInserter();
        if (inserterContext != null) {
            return Try.io(f).apply(inserterContext);
        }
        try (final ObjectInserter inserter = writeRepo.newObjectInserter()) {
            return Try.io(f).apply(inserter);
        }
    }
}
