package jp.ac.titech.c.se.stein.core;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.eclipse.jgit.lib.AnyObjectId;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.GpgSignature;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefRename;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.core.EntrySet.Entry;
import jp.ac.titech.c.se.stein.core.Try.IOThrowableFunction;

public class RepositoryAccess {
    private static final Logger log = LoggerFactory.getLogger(RepositoryAccess.class);

    public static final ObjectId[] NO_PARENTS = new ObjectId[0];

    protected final Repository repo;

    protected final NoteMap defaultNotes;

    protected boolean isDryRunning = false;

    public void setDryRunning(final boolean isDryRunning) {
        this.isDryRunning = isDryRunning;
        log.debug("Set the dry running mode of {} to {}", repo.getDirectory(), isDryRunning);
    }

    public RepositoryAccess(final Repository repo) {
        this.repo = repo;
        this.defaultNotes = readNote(Context.init());
    }

    // walk

    /**
     * Returns a RevWalk object.
     */
    public RevWalk walk(@SuppressWarnings("unused") final Context c) {
        final RevWalk walk = new RevWalk(repo);
        walk.sort(RevSort.TOPO, true);
        walk.sort(RevSort.REVERSE, true);
        return walk;
    }

    // Retrieving and checking objects

    /** Retrieve Ref object of given name. */
    public Ref getRef(final String name, final Context c) {
        return Try.io(c, () -> repo.getRefDatabase().findRef(name));
    }

    /**
     * Retrieves all Ref objects.
     */
    public List<Ref> getRefs(final Context c) {
        return Try.io(c, () -> repo.getRefDatabase().getRefs());
    }

    /**
     * Specifies the target object that the given ref indicates.
     */
    public ObjectId getRefTarget(final Ref ref, final Context c) {
        final Ref peeled = Try.io(c, () -> repo.getRefDatabase().peel(ref));
        return peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : ref.getObjectId();
    }

    /**
     * Specifies the type of the given object.
     */
    public int getObjectType(final ObjectId id, final Context c) {
        try (final RevWalk walk = new RevWalk(repo)) {
            final RevObject object = Try.io(c, () -> walk.parseAny(id));
            return object.getType();
        }
    }

    /**
     * Extracts a rev object.
     */
    public AnyObjectId parseAny(final ObjectId id, final Context c) {
        try (final RevWalk walk = new RevWalk(repo)) {
            return Try.io(c, () -> walk.parseAny(id));
        }
    }

    /**
     * Extracts a tag object.
     */
    public RevTag parseTag(final ObjectId id, final Context c) {
        try (final RevWalk walk = new RevWalk(repo)) {
            return Try.io(c, () -> walk.parseTag(id));
        }
    }

    /**
     * Tests whether the given ref indicates a tag.
     */
    public boolean isTag(final Ref ref, final Context c) {
        final Ref peeled = Try.io(c, () -> repo.getRefDatabase().peel(ref));
        return peeled.getPeeledObjectId() != null;
    }

    // Reading and writing objects

    /**
     * Reads a tree object.
     */
    public List<Entry> readTree(final ObjectId treeId, final String path, final Context c) {
        final List<Entry> result = new ArrayList<>();
        Try.io(c, () -> {
            try (final TreeWalk walk = new TreeWalk(repo)) {
                walk.addTree(treeId);
                walk.setRecursive(false);
                while (walk.next()) {
                    result.add(new Entry(walk.getFileMode().getBits(), walk.getNameString(), walk.getObjectId(0), path));
                }
            }
        });
        return result;
    }

    /**
     * Writes tree entries to a tree object.
     */
    public ObjectId writeTree(final Collection<Entry> entries, final Context c) {
        final TreeFormatter f = new TreeFormatter();
        for (final Entry e : sortEntries(entries, c)) {
            f.append(e.name, FileMode.fromBits(e.mode), e.id);
        }
        return insert(ins -> isDryRunning ? ins.idFor(f) : ins.insert(f), c);
    }

    /**
     * Sorts tree entries.
     */
    public Collection<Entry> sortEntries(final Collection<Entry> entries, final Context c) {
        final SortedMap<String, Entry> map = new TreeMap<>();
        for (final Entry e : entries) {
            final String key = e.name + (e.isTree() ? "/" : "");
            if (map.containsKey(key)) {
                log.warn("Entry occurred twice: {}, found: {}, new: {} {}", e.getPath(), map.get(key).id.name(), e.id.name(), c);
            }
            map.put(key, e);
        }
        return map.values();
    }

    /**
     * Reads a blob object.
     */
    public byte[] readBlob(final ObjectId blobId, final Context c) {
        return Try.io(c, () -> repo.getObjectDatabase().open(blobId, Constants.OBJ_BLOB).getBytes());
    }

    /**
     * Writes data to a blob object.
     */
    public ObjectId writeBlob(final byte[] data, final Context c) {
        return insert(ins -> isDryRunning ? ins.idFor(Constants.OBJ_BLOB, data) : ins.insert(Constants.OBJ_BLOB, data), c);
    }

    /**
     * Computes the size of a blob object.
     */
    public long getBlobSize(final ObjectId blobId, final Context c) {
        return Try.io(c, () -> repo.getObjectDatabase().open(blobId, Constants.OBJ_BLOB).getSize());
    }

    /**
     * Writes a commit object.
     */
    public ObjectId writeCommit(final ObjectId[] parentIds, final ObjectId treeId, final PersonIdent author, final PersonIdent committer,
            final Charset encoding, final GpgSignature signature, final String message, final Context c) {
        final CommitBuilder builder = new CommitBuilder();
        builder.setParentIds(parentIds);
        builder.setTreeId(treeId);
        builder.setAuthor(author);
        builder.setCommitter(committer);
        if (encoding != null) {
            builder.setEncoding(encoding);
        }
        if (signature != null) {
            builder.setGpgSignature(signature);
        }
        builder.setMessage(message);
        return insert(ins -> isDryRunning ? ins.idFor(Constants.OBJ_COMMIT, builder.build()) : ins.insert(builder), c);
    }

    public ObjectId writeCommit(final ObjectId[] parentIds, final ObjectId treeId, final PersonIdent author, final PersonIdent committer, final String message, final Context c) {
        return writeCommit(parentIds, treeId, author, committer, null, null, message, c);
    }

    // Notes

    /**
     * Add a note to the default notes.
     */
    public void addNote(final ObjectId commitId, final String note, final Context c) {
        addNote(defaultNotes, commitId, note, c);
    }

    /**
     * Add a note to notes.
     */
    public void addNote(final NoteMap notes, final ObjectId commitId, final String note, final Context c) {
        if (note != null) {
            final ObjectId blob = writeBlob(note.getBytes(), c);
            Try.io(() -> notes.set(commitId, blob));
        }
    }

    /**
     * Writes default notes if at least one exists.
     */
    public void writeNotes(final Context c) {
        if (defaultNotes.iterator().hasNext()) {
            writeNotes(defaultNotes, c);
        }
    }

    /**
     * Writes notes.
     */
    public void writeNotes(final NoteMap notes, final Context c) {
        writeNotes(notes, Constants.R_NOTES_COMMITS, c);
    }

    public void writeNotes(final NoteMap notes, final String ref, final Context c) {
        final ObjectId treeId = isDryRunning ? ObjectId.zeroId() : insert(notes::writeTree, c);
        // TODO building PersonIdent better.
        final PersonIdent ident = new PersonIdent(repo);
        final String message = "Notes added by 'git notes add'";
        final ObjectId commit = writeCommit(NO_PARENTS, treeId, ident, ident, message, c);

        applyRefUpdate(new RefEntry(ref, commit), c);

    }

    public void eachNote(final NoteMap notes, final BiConsumer<ObjectId, byte[]> f, final Context c) {
        for (final Note note : notes) {
            final ObjectId id = ObjectId.fromString(note.getName());
            final byte[] body = readBlob(note.getData(), c);
            f.accept(id, body);
        }
    }

    public void eachNote(final BiConsumer<ObjectId, byte[]> f, final Context c) {
        eachNote(defaultNotes, f, c);
    }

    public NoteMap readNote(final Context c) {
        return readNote(Constants.R_NOTES_COMMITS, c);
    }

    public NoteMap readNote(final String noteRef, final Context c) {
        final Ref targetRef = getRef(noteRef, c);
        if (targetRef == null) {
            return NoteMap.newEmptyMap();
        }
        return Try.io(c, () -> {
            try (final RevWalk walk = new RevWalk(repo)) {
                final RevCommit noteCommit = walk.parseCommit(getRefTarget(targetRef, c));
                return NoteMap.read(walk.getObjectReader(), noteCommit);
            }
        });
    }

    /**
     * Writes a tag object.
     */
    public ObjectId writeTag(final ObjectId objectId, final int type, final String tag, final PersonIdent tagger, final String message, final Context c) {
        final TagBuilder builder = new TagBuilder();
        builder.setObjectId(objectId, type);
        builder.setTag(tag);
        builder.setTagger(tagger);
        builder.setMessage(message);
        return insert(ins -> isDryRunning ? ins.idFor(Constants.OBJ_TAG, builder.build()) : ins.insert(builder), c);
    }

    // Ref manipulation

    /**
     * Applies ref update.
     */
    public void applyRefUpdate(final RefEntry entry, final Context c) {
        if (isDryRunning) {
            return;
        }
        Try.io(c, () -> {
            final RefUpdate cmd = repo.getRefDatabase().newUpdate(entry.name, false);
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
     * Applies ref delete.
     */
    public void applyRefDelete(final RefEntry entry, final Context c) {
        if (isDryRunning) {
            return;
        }
        Try.io(c, () -> {
            final RefUpdate cmd = repo.getRefDatabase().newUpdate(entry.name, false);
            cmd.setForceUpdate(true);
            cmd.delete();
        });
    }

    /**
     * Applies ref rename.
     */
    public void applyRefRename(final String name, final String newName, final Context c) {
        if (isDryRunning) {
            return;
        }
        Try.io(c, () -> {
            final RefRename cmd = repo.getRefDatabase().newRename(name, newName);
            cmd.rename();
        });
    }

    // Handling ObjectInserter

    /**
     * Opens and provides an object inserter.
     */
    public void openInserter(final Consumer<ObjectInserter> f, @SuppressWarnings("unused") final Context c) {
        try (final ObjectInserter ins = repo.newObjectInserter()) {
            f.accept(ins);
        }
    }

    /**
     * Generates an object inserter.
     */
    public ObjectInserter getInserter(@SuppressWarnings("unused") final Context c) {
        return repo.newObjectInserter();
    }

    /**
     * Inserts objects using a prepared object inserter.
     */
    public <R> R insert(final IOThrowableFunction<ObjectInserter, R> f, final Context c) {
        final ObjectInserter inserterContext = c.getInserter();
        if (inserterContext != null) {
            return Try.io(f).apply(inserterContext);
        }
        try (final ObjectInserter inserter = repo.newObjectInserter()) {
            return Try.io(f).apply(inserter);
        }
    }
}
