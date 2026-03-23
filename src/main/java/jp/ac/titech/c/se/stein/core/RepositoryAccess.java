package jp.ac.titech.c.se.stein.core;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.jgit.RevWalk;
import jp.ac.titech.c.se.stein.jgit.TreeFormatter;
import lombok.Getter;
import org.eclipse.jgit.lib.*;
import org.eclipse.jgit.notes.Note;
import org.eclipse.jgit.notes.NoteMap;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.core.Try.IOThrowableFunction;

/**
 * Low-level operations on a Git repository: reading and writing blobs, trees, commits, tags,
 * notes, and refs.
 */
public class RepositoryAccess implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(RepositoryAccess.class);

    public static final ObjectId[] NO_PARENTS = new ObjectId[0];

    public final Repository repo;

    @Getter
    protected final NoteMap defaultNotes;

    protected boolean isDryRunning = false;

    /**
     * Enables or disables dry-run mode.
     * When enabled, write operations compute object IDs without persisting to the repository.
     */
    public void setDryRunning(final boolean isDryRunning) {
        this.isDryRunning = isDryRunning;
        log.debug("Set the dry running mode of {} to {}", repo.getDirectory(), isDryRunning);
    }

    public RepositoryAccess(final Repository repo) {
        this.repo = repo;
        this.defaultNotes = readNotes();
    }

    @Override
    public void close() {
        repo.close();
    }

    /**
     * Sets up alternates so that this repository shares objects from {@code source},
     * skipping writes for objects that already exist.
     *
     * @param useRelative if true, write a relative path; otherwise, write an absolute path
     */
    public RepositoryAccess setupAlternates(final Repository source, final boolean useRelative) {
        Try.io(() -> {
            final Path objs = repo.getDirectory().toPath().resolve("objects");
            final Path srcObjs = source.getDirectory().toPath().resolve("objects");
            final String entry = useRelative
                    ? objs.toAbsolutePath().relativize(srcObjs.toAbsolutePath()).toString()
                    : srcObjs.toAbsolutePath().toString();
            final Path info = objs.resolve("info");
            Files.createDirectories(info);
            Files.writeString(info.resolve("alternates"), entry + "\n");
            log.debug("Set alternates: {}", entry);
        });
        return this;
    }

    // walk

    /**
     * Creates a {@link RevWalk} sorted in topological-reverse order.
     */
    public RevWalk walk() {
        final RevWalk walk = new RevWalk(repo);
        walk.sort(RevSort.TOPO, true);
        walk.sort(RevSort.REVERSE, true);
        return walk;
    }

    /**
     * Returns all commits reachable from the given ref in topological-reverse order (oldest first).
     */
    public List<RevCommit> collectCommits(final String refName) {
        final Ref ref = getRef(refName);
        if (ref == null) {
            return List.of();
        }
        final List<RevCommit> result = new ArrayList<>();
        try (final RevWalk walk = walk()) {
            Try.io(() -> walk.memoMarkStart(walk.parseCommit(ref.getObjectId())));
            walk.forEach(result::add);
        }
        return result;
    }

    /**
     * Returns the head commit of the given ref, or {@code null} if the ref does not exist.
     */
    public RevCommit getHead(final String refName) {
        final Ref ref = getRef(refName);
        if (ref == null) {
            return null;
        }
        try (final RevWalk walk = new RevWalk(repo)) {
            return Try.io(() -> walk.parseCommit(ref.getObjectId()));
        }
    }

    /**
     * Recursively flattens a tree, returning all blob entries.
     */
    public List<Entry> flattenTree(final ObjectId treeId) {
        return flattenTree(treeId, null);
    }

    /**
     * Recursively flattens a tree, returning all blob entries.
     */
    public List<Entry> flattenTree(final ObjectId treeId, final String path) {
        final List<Entry> entries = readTree(treeId, path);
        final List<Entry> files = new ArrayList<>();
        for (final Entry e : entries) {
            if (e.isTree()) {
                files.addAll(flattenTree(e.getId(), e.getPath()));
            } else {
                files.add(e);
            }
        }
        return files;
    }

    // Retrieving and checking objects

    /**
     * Returns the {@link Ref} for the given name, or {@code null} if not found.
     */
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
     * Returns the ultimate target object ID of the given ref, peeling annotated tags.
     */
    public ObjectId getRefTarget(final Ref ref) {
        final Ref peeled = Try.io(() -> repo.getRefDatabase().peel(ref));
        return peeled.getPeeledObjectId() != null ? peeled.getPeeledObjectId() : ref.getObjectId();
    }

    /**
     * Returns the object type (e.g., {@link Constants#OBJ_COMMIT}) of the given object.
     */
    public int getObjectType(final ObjectId id) {
        try (final RevWalk walk = new RevWalk(repo)) {
            final RevObject object = Try.io(() -> walk.parseAny(id));
            return object.getType();
        }
    }

    /**
     * Parses a revision-spec string to make an ObjectId.
     */
    public ObjectId resolve(final String rev) {
        return Try.io(() -> repo.resolve(rev));
    }

    /**
     * Parses the given object ID into a {@link RevObject}.
     */
    public AnyObjectId parseAny(final ObjectId id) {
        try (final RevWalk walk = new RevWalk(repo)) {
            return Try.io(() -> walk.parseAny(id));
        }
    }

    /**
     * Resolves a revision-spec string and parses it into a {@link RevObject}.
     */
    public AnyObjectId parseAny(final String rev) {
        return parseAny(resolve(rev));
    }

    /**
     * Parses the given object ID as a tag.
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
    public List<Entry> readTree(final ObjectId treeId, final String path) {
        final List<Entry> result = new ArrayList<>();
        Try.io(() -> {
            // Do not use TreeWalk here; TreeWalk does not provide a way to access the mode bit directly.
            // Its API getFileMode() outputs a FileMode, but it normalizes different mode bits into a standard one.
            try (final ObjectReader reader = repo.newObjectReader()) {
                final CanonicalTreeParser p = new CanonicalTreeParser(null, reader, treeId);
                while (!p.eof()) {
                    result.add(Entry.of(p.getEntryRawMode(), p.getEntryPathString(), p.getEntryObjectId(), path));
                    p.next();
                }
            }
        });
        return result;
    }

    /**
     * Writes tree entries to a tree object.
     */
    public ObjectId writeTree(final Collection<Entry> entries, final Context writingContext) {
        final TreeFormatter f = new TreeFormatter();
        resolveNameConflicts(entries).stream()
                .sorted(Comparator.comparing(Entry::sortKey))
                .forEach(e -> f.append(e.name, e.mode, e.id));
        return insert(ins -> isDryRunning ? f.computeId(ins) : f.insertTo(ins), writingContext);
    }

    /**
     * Resolves name conflicts by appending {@code @N} suffixes to duplicate names.
     */
    public static List<Entry> resolveNameConflicts(final Collection<Entry> entries) {
        final Map<String, Integer> counter = new HashMap<>();
        final List<Entry> result = new ArrayList<>();
        for (final Entry e : entries) {
            if (counter.containsKey(e.name)) {
                // Find a possible filename with a number suffix
                int suffix = counter.get(e.name) + 1;
                while (counter.containsKey(e.name + "@" + suffix)) {
                    log.debug("{}@{} was already used", e.name, suffix);
                    suffix++;
                }
                log.debug("Entry occurred twice: {}, used {}@{} for {} instead", e.getPath(), e.name, suffix, e.id.name());
                final Entry newEntry = Entry.of(e.mode, e.name + "@" + suffix, e.id, e.directory);
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
     * Copies a tree to another repo.
     */
    public ObjectId copyTree(final ObjectId treeId, final RepositoryAccess target, final Context c) {
        final List<Entry> entries = new ArrayList<>();
        for (final Entry e : readTree(treeId, null)) {
            entries.add(switch (e.getType()) {
                case tree -> Entry.of(e.getMode(), e.getName(), copyTree(e.getId(), target, c));
                case blob -> Entry.of(e.getMode(), e.getName(), copyBlob(e.getId(), target, c));
                default -> e;
            });
        }
        return target.writeTree(entries, c);
    }

    /**
     * Copies a blob to another repo.
     */
    public ObjectId copyBlob(final ObjectId blobId, final RepositoryAccess target, final Context c) {
        return target.writeBlob(readBlob(blobId), c);
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
            final String message, final Charset encoding, final GpgSignature signature, final Context writingContext) {
        final CommitBuilder builder = new CommitBuilder();
        builder.setParentIds(parentIds);
        builder.setTreeId(treeId);
        builder.setAuthor(author);
        builder.setCommitter(committer);
        builder.setMessage(message);
        if (encoding != null) {
            builder.setEncoding(encoding);
        }
        if (signature != null) {
            builder.setGpgSignature(signature);
        }
        return insert(ins -> isDryRunning ? ins.idFor(Constants.OBJ_COMMIT, builder.build()) : ins.insert(builder), writingContext);
    }

    /**
     * Shorthand for {@link #writeCommit} without encoding or signature.
     */
    public ObjectId writeCommit(final ObjectId[] parentIds, final ObjectId treeId, final PersonIdent author, final PersonIdent committer, final String message, final Context writingContext) {
        return writeCommit(parentIds, treeId, author, committer, message, null, null, writingContext);
    }

    // Notes

    /**
     * Adds a note (as a blob) to the given note map.
     */
    public void addNote(final NoteMap notes, final ObjectId commitId, final byte[] content, final Context writingContext) {
        if (content != null) {
            final ObjectId blob = writeBlob(content, writingContext);
            Try.io(() -> notes.set(commitId, blob));
        }
    }

    /**
     * Reads a note for the given commit from the note map, or returns {@code null} if absent.
     */
    public byte[] readNote(final NoteMap notes, final ObjectId commitId) {
        final ObjectId blobId = Try.io(() -> notes.get(commitId));
        if (blobId == null) {
            return null;
        }
        return readBlob(blobId);
    }

    /**
     * Writes notes to the default notes ref ({@code refs/notes/commits}).
     */
    public void writeNotes(final NoteMap notes, final Context writingContext) {
        writeNotes(notes, Constants.R_NOTES_COMMITS, writingContext);
    }

    /**
     * Writes notes to the specified ref.
     */
    public void writeNotes(final NoteMap notes, final String ref, final Context writingContext) {
        final ObjectId treeId = isDryRunning ? ObjectId.zeroId() : insert(notes::writeTree, writingContext);
        // TODO building PersonIdent better.
        final PersonIdent ident = new PersonIdent(repo);
        final String message = "Notes added by 'git notes add'";
        final ObjectId commit = writeCommit(NO_PARENTS, treeId, ident, ident, message, writingContext);

        applyRefUpdate(new RefEntry(ref, commit));

    }

    /**
     * Iterates over all notes in the given map, passing each commit ID and note body.
     */
    public void forEachNote(final NoteMap notes, final BiConsumer<ObjectId, byte[]> f) {
        for (final Note note : notes) {
            final ObjectId id = ObjectId.fromString(note.getName());
            final byte[] body = readBlob(note.getData());
            f.accept(id, body);
        }
    }

    /**
     * Reads notes from the default notes ref ({@code refs/notes/commits}).
     */
    public NoteMap readNotes() {
        return readNotes(Constants.R_NOTES_COMMITS);
    }

    /**
     * Reads notes from the specified ref, returning an empty map if the ref does not exist.
     */
    public NoteMap readNotes(final String noteRef) {
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
     * Creates or updates a ref. Handles both direct and symbolic refs.
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
     * Deletes the specified ref.
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
     * Renames a ref.
     */
    public void applyRefRename(final String name, final String newName) {
        if (isDryRunning) {
            return;
        }
        Try.io(() -> repo.getRefDatabase().newRename(name, newName).rename());
    }

    // Handling ObjectInserter

    /**
     * Opens an {@link ObjectInserter} and executes the given operation, then closes it.
     */
    public void openInserter(final Consumer<ObjectInserter> f) {
        try (final ObjectInserter ins = repo.newObjectInserter()) {
            f.accept(ins);
            Try.io(ins::flush);
        }
    }

    /**
     * Creates a new {@link ObjectInserter}. The caller is responsible for closing it.
     */
    public ObjectInserter getInserter() {
        return repo.newObjectInserter();
    }

    /**
     * Executes an insert operation, using the inserter from the context if available,
     * or creating a new one otherwise.
     */
    public <R> R insert(final IOThrowableFunction<ObjectInserter, R> f, final Context writingContext) {
        final ObjectInserter inserterContext = writingContext.getInserter();
        if (inserterContext != null) {
            return Try.io(f).apply(inserterContext);
        }
        try (final ObjectInserter inserter = repo.newObjectInserter()) {
            final R result = Try.io(f).apply(inserter);
            Try.io(inserter::flush);
            return result;
        }
    }
}
