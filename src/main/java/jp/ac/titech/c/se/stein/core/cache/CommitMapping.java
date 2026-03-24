package jp.ac.titech.c.se.stein.core.cache;

import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import lombok.Getter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages source-to-target commit ID mapping with support for notes-based restoration.
 *
 * <p>When an incremental transformation is performed, commits that were already
 * transformed in a previous run should not be re-processed. git-stein records the
 * source commit ID as a git note on each target commit, so the target repository
 * itself serves as persistent storage for commit mappings.</p>
 *
 * <p>Loading is two-phase. On initialization, only the ref tips of the target are
 * examined: for each target ref, the note on the tip commit is read to recover the
 * corresponding source commit ID. These are registered in the mapping and also
 * collected as "uninteresting" points so that the source RevWalk stops at
 * already-processed commits. This covers the common case (linear history, no merges
 * from old branches). If a merge commit references an old source commit not reachable
 * from any current ref tip, the mapping will miss, and a full scan of all target notes
 * is triggered lazily (at most once) to load the remaining entries.</p>
 */
public class CommitMapping extends AbstractMap<ObjectId, ObjectId> {
    private static final Logger log = LoggerFactory.getLogger(CommitMapping.class);

    private final Map<ObjectId, ObjectId> map = new HashMap<>();

    /**
     * Source commit IDs of previously processed ref tips.
     * These should be marked as uninteresting in the source RevWalk.
     */
    @Getter
    private final List<ObjectId> previousSourceTips = new ArrayList<>();

    private NoteObjectIdMap notesMap;
    private volatile boolean notesFullyLoaded = false;

    /**
     * Restores commit mapping from the target repository's notes.
     * Only ref tips are read eagerly.
     *
     * @param notesRef the notes ref to read from (e.g., {@code refs/notes/git-stein-prev})
     */
    public void restoreFromTarget(RepositoryAccess target, String notesRef) {
        final List<Ref> targetRefs = target.getRefs();
        if (targetRefs.isEmpty()) {
            return;
        }

        notesMap = new NoteObjectIdMap(target.readNotes(notesRef), target);

        for (final Ref ref : targetRefs) {
            final ObjectId targetTipId = target.getRefTarget(ref);
            if (targetTipId == null || target.getObjectType(targetTipId) != Constants.OBJ_COMMIT) {
                continue;
            }
            final ObjectId sourceTipId = notesMap.get(targetTipId);
            if (sourceTipId == null) {
                continue;
            }
            map.put(sourceTipId, targetTipId);
            previousSourceTips.add(sourceTipId);
            log.debug("Restored commit mapping from note: {} -> {} (ref: {})",
                    sourceTipId.name(), targetTipId.name(), ref.getName());
        }

        if (!previousSourceTips.isEmpty()) {
            log.info("Restored {} commit mappings from target notes", previousSourceTips.size());
        }
    }


    @Override
    public ObjectId get(Object key) {
        final ObjectId v = map.get(key);
        if (v != null) {
            return v;
        }
        if (!notesFullyLoaded && notesMap != null) {
            loadAllNotes();
            return map.get(key);
        }
        return null;
    }

    @Override
    public ObjectId put(ObjectId key, ObjectId value) {
        return map.put(key, value);
    }

    @Override
    public int size() {
        return map.size();
    }

    @Override
    public Set<Entry<ObjectId, ObjectId>> entrySet() {
        return map.entrySet();
    }

    /**
     * Loads all notes into the mapping. Called at most once, when a lookup
     * misses on a commit not reachable from any ref tip (e.g., old merge parent).
     */
    private synchronized void loadAllNotes() {
        if (notesFullyLoaded) {
            return;
        }
        log.info("Loading full notes for commit mapping fallback");
        notesMap.forEach((targetId, sourceId) -> map.put(sourceId, targetId));
        log.info("Loaded commit mappings, total {} entries", map.size());
        notesFullyLoaded = true;
    }
}
