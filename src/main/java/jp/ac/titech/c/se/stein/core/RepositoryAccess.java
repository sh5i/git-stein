package jp.ac.titech.c.se.stein.core;

import java.nio.charset.Charset;
import java.util.*;
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

import jp.ac.titech.c.se.stein.core.ColdEntry.HashEntry;
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
        this.defaultNotes = readNote();
    }

    // walk

    /**
     * Returns a RevWalk object.
     */
    public RevWalk walk() {
        final RevWalk walk = new RevWalk(repo);
        walk.sort(RevSort.TOPO, true);
        walk.sort(RevSort.REVERSE, true);
        return walk;
    }

    // Retrieving and checking objects

    /** Retrieve Ref object of given name. */
    public Ref getRef(final String name) {
        return Try.io(() -> repo.getRefDatabase().findRef(name));
    }

    /**
     * Retrieves all Ref objects.
     */
    public List<Ref> getRefs() {
        return Try.io(() -> repo.getRefDatabase().getRefs());
    }

    /**
     * Specifies the target object that the given ref indicates.
     */
    public ObjectId getRefTarget(final Ref ref) {
        final Ref peeled = Try.io(() -> repo.getRefDatabase().peel(ref));
        return peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : ref.getObjectId();
    }

    /**
     * Specifies the type of the given object.
     */
    public int getObjectType(final ObjectId id) {
        try (final RevWalk walk = new RevWalk(repo)) {
            final RevObject object = Try.io(() -> walk.parseAny(id));
            return object.getType();
        }
    }

    /**
     * Extracts a rev object.
     */
    public AnyObjectId parseAny(final ObjectId id) {
        try (final RevWalk walk = new RevWalk(repo)) {
            return Try.io(() -> walk.parseAny(id));
        }
    }

    /**
     * Extracts a tag object.
     */
    public RevTag parseTag(final ObjectId id) {
        try (final RevWalk walk = new RevWalk(repo)) {
            return Try.io(() -> walk.parseTag(id));
        }
    }

    /**
     * Tests whether the given ref indicates a tag.
     */
    public boolean isTag(final Ref ref) {
        final Ref peeled = Try.io(() -> repo.getRefDatabase().peel(ref));
        return peeled.getPeeledObjectId() != null;
    }

    // Reading and writing objects

    /**
     * Reads a tree object.
     */
    public List<HashEntry> readTree(final ObjectId treeId, final String path) {
        final List<HashEntry> result = new ArrayList<>();
        Try.io(() -> {
            try (final TreeWalk walk = new TreeWalk(repo)) {
                walk.addTree(treeId);
                walk.setRecursive(false);
                while (walk.next()) {
                    result.add(new HashEntry(walk.getFileMode().getBits(), walk.getNameString(), walk.getObjectId(0), path));
                }
            }
        });
        return result;
    }

    /**
     * Writes tree entries to a tree object.
     */
    public ObjectId writeTree(final Collection<HashEntry> entries, final Context writingContext) {
        final TreeFormatter f = new TreeFormatter();
        resolveNameConflicts(entries).stream()
                .sorted(Comparator.comparing(HashEntry::sortKey))
                .forEach(e -> f.append(e.name, FileMode.fromBits(e.mode), e.id));
        return insert(ins -> isDryRunning ? ins.idFor(f) : ins.insert(f), writingContext);
    }

    /**
     * Resolve name conflicts.
     */
    public List<HashEntry> resolveNameConflicts(final Collection<HashEntry> entries) {
        final Map<String, Integer> counter = new HashMap<>();
        final List<HashEntry> result = new ArrayList<>();
        for (final HashEntry e : entries) {
            if (counter.containsKey(e.name)) {
                // Find a possible filename with a number suffix
                int suffix = counter.get(e.name) + 1;
                while (counter.containsKey(e.name + "_" + suffix)) {
                    log.debug("{}_{} was already used", e.name, suffix);
                    suffix++;
                }
                log.debug("Entry occurred twice: {}, used {}_{} for {} instead", e.getPath(), e.name, suffix, e.id.name());
                final HashEntry newEntry = new HashEntry(e.mode, e.name + "_" + suffix, e.id, e.directory);
                counter.put(e.name, suffix);
                counter.put(newEntry.name, 1);
                result.add(newEntry);
            } else {
                counter.put(e.name, 1);
                result.add(e);
            }
        }
        return result;
    }

    /**
     * Reads a blob object.
     */
    public byte[] readBlob(final ObjectId blobId) {
        return Try.io(() -> repo.getObjectDatabase().open(blobId, Constants.OBJ_BLOB).getBytes());
    }

    /**
     * Writes data to a blob object.
     */
    public ObjectId writeBlob(final byte[] data, final Context writingContext) {
        return insert(ins -> isDryRunning ? ins.idFor(Constants.OBJ_BLOB, data) : ins.insert(Constants.OBJ_BLOB, data), writingContext);
    }

    /**
     * Computes the size of a blob object.
     */
    public long getBlobSize(final ObjectId blobId) {
        return Try.io(() -> repo.getObjectDatabase().open(blobId, Constants.OBJ_BLOB).getSize());
    }

    /**
     * Writes a commit object.
     */
    public ObjectId writeCommit(final ObjectId[] parentIds, final ObjectId treeId, final PersonIdent author, final PersonIdent committer,
            final Charset encoding, final GpgSignature signature, final String message, final Context writingContext) {
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
        return insert(ins -> isDryRunning ? ins.idFor(Constants.OBJ_COMMIT, builder.build()) : ins.insert(builder), writingContext);
    }

    public ObjectId writeCommit(final ObjectId[] parentIds, final ObjectId treeId, final PersonIdent author, final PersonIdent committer, final String message, final Context writingContext) {
        return writeCommit(parentIds, treeId, author, committer, null, null, message, writingContext);
    }

    // Notes

    /**
     * Add a note to the default notes.
     */
    public void addNote(final ObjectId commitId, final String note, final Context writingContext) {
        addNote(defaultNotes, commitId, note, writingContext);
    }

    /**
     * Add a note to notes.
     */
    public void addNote(final NoteMap notes, final ObjectId commitId, final String note, final Context writingContext) {
        if (note != null) {
            final ObjectId blob = writeBlob(note.getBytes(), writingContext);
            Try.io(() -> notes.set(commitId, blob));
        }
    }

    /**
     * Writes default notes if at least one exists.
     */
    public void writeNotes(final Context writingContext) {
        if (defaultNotes.iterator().hasNext()) {
            writeNotes(defaultNotes, writingContext);
        }
    }

    /**
     * Writes notes.
     */
    public void writeNotes(final NoteMap notes, final Context writingContext) {
        writeNotes(notes, Constants.R_NOTES_COMMITS, writingContext);
    }

    public void writeNotes(final NoteMap notes, final String ref, final Context writingContext) {
        final ObjectId treeId = isDryRunning ? ObjectId.zeroId() : insert(notes::writeTree, writingContext);
        // TODO building PersonIdent better.
        final PersonIdent ident = new PersonIdent(repo);
        final String message = "Notes added by 'git notes add'";
        final ObjectId commit = writeCommit(NO_PARENTS, treeId, ident, ident, message, writingContext);

        applyRefUpdate(new RefEntry(ref, commit));

    }

    public void eachNote(final NoteMap notes, final BiConsumer<ObjectId, byte[]> f) {
        for (final Note note : notes) {
            final ObjectId id = ObjectId.fromString(note.getName());
            final byte[] body = readBlob(note.getData());
            f.accept(id, body);
        }
    }

    public void eachNote(final BiConsumer<ObjectId, byte[]> f) {
        eachNote(defaultNotes, f);
    }

    public NoteMap readNote() {
        return readNote(Constants.R_NOTES_COMMITS);
    }

    public NoteMap readNote(final String noteRef) {
        final Ref targetRef = getRef(noteRef);
        if (targetRef == null) {
            return NoteMap.newEmptyMap();
        }
        return Try.io(() -> {
            try (final RevWalk walk = new RevWalk(repo)) {
                final RevCommit noteCommit = walk.parseCommit(getRefTarget(targetRef));
                return NoteMap.read(walk.getObjectReader(), noteCommit);
            }
        });
    }

    /**
     * Writes a tag object.
     */
    public ObjectId writeTag(final ObjectId objectId, final int type, final String tag, final PersonIdent tagger, final String message, final Context writingContext) {
        final TagBuilder builder = new TagBuilder();
        builder.setObjectId(objectId, type);
        builder.setTag(tag);
        if (tagger != null) {
            builder.setTagger(tagger);
        }
        builder.setMessage(message);
        return insert(ins -> isDryRunning ? ins.idFor(Constants.OBJ_TAG, builder.build()) : ins.insert(builder), writingContext);
    }

    // Ref manipulation

    /**
     * Applies ref update.
     */
    public void applyRefUpdate(final RefEntry entry) {
        if (isDryRunning) {
            return;
        }
        Try.io(() -> {
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
    public void applyRefDelete(final RefEntry entry) {
        if (isDryRunning) {
            return;
        }
        Try.io(() -> {
            final RefUpdate cmd = repo.getRefDatabase().newUpdate(entry.name, false);
            cmd.setForceUpdate(true);
            cmd.delete();
        });
    }

    /**
     * Applies ref rename.
     */
    public void applyRefRename(final String name, final String newName) {
        if (isDryRunning) {
            return;
        }
        Try.io(() -> repo.getRefDatabase().newRename(name, newName).rename());
    }

    // Handling ObjectInserter

    /**
     * Opens and provides an object inserter.
     */
    public void openInserter(final Consumer<ObjectInserter> f) {
        try (final ObjectInserter ins = repo.newObjectInserter()) {
            f.accept(ins);
        }
    }

    /**
     * Generates an object inserter.
     */
    public ObjectInserter getInserter() {
        return repo.newObjectInserter();
    }

    /**
     * Inserts objects using a prepared object inserter.
     */
    public <R> R insert(final IOThrowableFunction<ObjectInserter, R> f, final Context writingContext) {
        final ObjectInserter inserterContext = writingContext.getInserter();
        if (inserterContext != null) {
            return Try.io(f).apply(inserterContext);
        }
        try (final ObjectInserter inserter = repo.newObjectInserter()) {
            return Try.io(f).apply(inserter);
        }
    }
}
