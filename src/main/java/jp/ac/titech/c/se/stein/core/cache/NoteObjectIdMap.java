package jp.ac.titech.c.se.stein.core.cache;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.notes.NoteMap;

import java.util.function.BiConsumer;

/**
 * A view over a JGit NoteMap that interprets note bodies as ObjectIds (hex-encoded).
 * Supports both reading (get, forEach) and writing (add).
 */
public class NoteObjectIdMap {
    private final NoteMap notes;
    private final RepositoryAccess ra;

    public NoteObjectIdMap(NoteMap notes, RepositoryAccess ra) {
        this.notes = notes;
        this.ra = ra;
    }

    /**
     * Returns the NoteMap backing this view.
     */
    public NoteMap getNoteMap() {
        return notes;
    }

    /**
     * Reads the note on the given commit as an ObjectId.
     */
    public ObjectId get(ObjectId commitId) {
        return parseObjectId(ra.readNote(notes, commitId));
    }

    /**
     * Adds a note recording the given value as the note body on the given commit.
     */
    public void add(ObjectId commitId, ObjectId value, Context c) {
        final byte[] content = new byte[Constants.OBJECT_ID_STRING_LENGTH];
        value.copyTo(content, 0);
        ra.addNote(notes, commitId, content, c);
    }

    /**
     * Adds a note by forwarding raw note bytes (for chain forwarding).
     */
    public void addRaw(ObjectId commitId, byte[] rawNote, Context c) {
        ra.addNote(notes, commitId, rawNote, c);
    }

    /**
     * Iterates all notes, passing (annotatedId, bodyAsObjectId) pairs.
     */
    public void forEach(BiConsumer<ObjectId, ObjectId> consumer) {
        ra.forEachNote(notes, (annotatedId, body) -> {
            final ObjectId bodyId = parseObjectId(body);
            if (bodyId != null) {
                consumer.accept(annotatedId, bodyId);
            }
        });
    }

    /**
     * Writes the notes to the repository under the given ref.
     */
    public void write(String ref, Context c) {
        ra.writeNotes(notes, ref, c);
    }

    private static ObjectId parseObjectId(byte[] body) {
        if (body == null) {
            return null;
        }
        try {
            return ObjectId.fromString(new String(body));
        } catch (Exception e) {
            return null;
        }
    }
}
