package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.Context.Key;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Base for rewriters that drop selected commits from the history. A dropped commit is spliced out of the graph:
 * its children reconnect directly to its rewritten parents, so a dropped merge hands all
 * of its parents to its children and no branch is lost.
 * Subclasses decide which commits to drop by overriding {@link #shouldDrop}.
 *
 * Note that splicing reconnects the graph but does not minimize it: dropping a merge whose other
 * side was itself dropped can leave a parent that is now an ancestor of another parent. Removing
 * such redundant parents would need ancestry analysis and is intentionally out of scope here.
 */
@Slf4j
public abstract class CommitDropper extends RepositoryRewriter {
    /**
     * For each dropped commit, the rewritten parents that its children should splice onto.
     */
    private final Map<ObjectId, ObjectId[]> splicedParents = new HashMap<>();

    @Override
    protected ObjectId[] rewriteParents(final ObjectId[] parents, final Context c) {
        final List<ObjectId> result = new ArrayList<>();
        for (final ObjectId parent : parents) {
            for (final ObjectId p : spliceParent(parent)) {
                if (!p.equals(ZERO) && !result.contains(p)) {
                    result.add(p);
                }
            }
        }
        return result.toArray(new ObjectId[0]);
    }

    /**
     * Returns the rewritten parent(s) standing in for a source parent: a dropped parent expands into
     * its own spliced parents, a surviving parent maps through the commit mapping.
     */
    private ObjectId[] spliceParent(final ObjectId parent) {
        final ObjectId[] spliced = splicedParents.get(parent);
        if (spliced != null) {
            return spliced;
        }
        final ObjectId mapped = commitMapping.get(parent);
        return new ObjectId[]{mapped != null ? mapped : parent};
    }

    @Override
    protected ObjectId rewriteCommit(final RevCommit commit, final Context c) {
        final Context uc = c.with(Key.rev, commit, Key.commit, commit);
        final ObjectId[] parentIds = rewriteParents(commit.getParents(), uc);
        final ObjectId treeId = resolveRootTree(commit.getTree().getId(), uc);
        if (shouldDrop(commit, treeId, parentIds, uc)) {
            final ObjectId oldId = commit.getId().copy();
            splicedParents.put(oldId, parentIds);
            // A ref pointing straight at a dropped commit falls back to its first spliced parent, or
            // ZERO when it had none (a dropped root, whose children become roots themselves).
            final ObjectId fallback = parentIds.length >= 1 ? parentIds[0] : ZERO;
            commitMapping.put(oldId, fallback);
            log.debug("Drop commit: {} (splice {} parents) {}", oldId.name(), parentIds.length, c);
            return fallback;
        }
        return super.rewriteCommit(commit, uc);
    }

    /**
     * Decides whether to drop the given commit. The {@code parentIds} are the rewritten parents
     * after splicing, so a dropped parent already appears as its own parents (deduplicated, with
     * {@link #ZERO} removed).
     */
    protected abstract boolean shouldDrop(RevCommit commit, ObjectId treeId, ObjectId[] parentIds, Context c);
}
