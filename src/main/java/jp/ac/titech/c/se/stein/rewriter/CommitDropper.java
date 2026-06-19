package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.Try;
import jp.ac.titech.c.se.stein.jgit.RevWalk;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Base for rewriters that drop selected commits from the history.
 * Subclasses decide which commits to drop from each commit's own source characteristics,
 * by overriding {@link #shouldDrop}.
 *
 * A dropped commit is spliced out of the graph without being transformed:
 * its children reconnect directly to its parents,
 * so a dropped merge hands all of its parents to its children and no branch is lost.
 * Splicing can in turn leave a parent edge redundant, in which case it is pruned;
 * {@link #effectiveParents} defines exactly when.
 */
@Slf4j
public abstract class CommitDropper extends RepositoryRewriter {
    /**
     * For each dropped commit, the live commits it is replaced by.
     */
    private final Map<ObjectId, ObjectId[]> replacedBy = new HashMap<>();

    /**
     * Memoized source-graph ancestry ({@code base -> tip -> base is an ancestor of tip}).
     */
    private final Map<ObjectId, Map<ObjectId, Boolean>> ancestryCache = new HashMap<>();

    @Override
    protected ObjectId[] rewriteParents(final ObjectId[] parents, final Context c) {
        return effectiveParents(parents).stream().map(commitMapping::get).toArray(ObjectId[]::new);
    }

    @Override
    protected ObjectId rewriteCommit(final RevCommit commit, final Context c) {
        if (!shouldDrop(commit, c)) {
            return super.rewriteCommit(commit, c);
        }

        // Drop: splice the commit out without transforming it. Record its effective parents, which
        // its children reconnect onto, and map it to the first of them (ZERO if it had none, so a
        // ref straight at a dropped root, and its children, become roots).
        final List<ObjectId> effective = effectiveParents(commit.getParents());
        final ObjectId oldId = commit.getId().copy();
        replacedBy.put(oldId, effective.toArray(new ObjectId[0]));
        final ObjectId target = effective.isEmpty() ? ZERO : commitMapping.get(effective.get(0));
        commitMapping.put(oldId, target);
        log.debug("Drop commit: {} -> {} {}", oldId.name(), target.name(), c);
        return target;
    }

    /**
     * Decides whether to drop the given commit, from its own source characteristics.
     */
    protected abstract boolean shouldDrop(RevCommit commit, Context c);


    /**
     * The effective parents of a commit with the given original {@code parents}:
     * each dropped parent replaced by its {@link #representatives}, the resulting
     * survivors then pruned of the edges that splicing made redundant.
     *
     * A survivor is pruned when it is already an ancestor of another survivor,
     * unless an original parent it stands for was already such an ancestor in the source --
     * so only splice-created redundancy is removed and originally-redundant merges are kept.
     * Ancestry is decided on the source graph, and the result stays in source ids.
     */
    private List<ObjectId> effectiveParents(final ObjectId[] parents) {
        final Map<ObjectId, List<ObjectId>> origins = originsByRepresentative(parents);
        final List<ObjectId> survivors = new ArrayList<>(origins.keySet());
        final List<ObjectId> parentList = Arrays.asList(parents);
        final List<ObjectId> result = new ArrayList<>(survivors);
        result.removeIf(s -> redundantWithin(s, survivors)
                && origins.get(s).stream().noneMatch(p -> redundantWithin(p, parentList)));
        return result;
    }

    /**
     * The representatives of {@code commit} among the live commits:
     * {@code commit} itself if kept, or the parents it was replaced by if dropped.
     */
    private List<ObjectId> representatives(final ObjectId commit) {
        final ObjectId[] replacement = replacedBy.get(commit);
        return replacement != null ? Arrays.asList(replacement) : List.of(commit);
    }

    /**
     * The inverse of {@link #representatives} over {@code commits}:
     * maps each representative to the commits it stands for, preserving order.
     */
    private Map<ObjectId, List<ObjectId>> originsByRepresentative(final ObjectId[] commits) {
        final Map<ObjectId, List<ObjectId>> result = new LinkedHashMap<>();
        for (final ObjectId origin : commits) {
            for (final ObjectId s : representatives(origin)) {
                result.computeIfAbsent(s, k -> new ArrayList<>()).add(origin);
            }
        }
        return result;
    }

    /**
     * Whether {@code x} is an ancestor of another commit in {@code commits} --
     * so already reachable through it, which makes the edge to {@code x} redundant.
     */
    private boolean redundantWithin(final ObjectId x, final Collection<ObjectId> commits) {
        return commits.stream().anyMatch(y -> !y.equals(x) && isAncestor(x, y));
    }

    /**
     * Tests whether {@code base} is an ancestor of {@code tip} in the source history, memoized.
     */
    private boolean isAncestor(final ObjectId base, final ObjectId tip) {
        return ancestryCache
                .computeIfAbsent(base, k -> new HashMap<>())
                .computeIfAbsent(tip, k -> Try.io(() -> {
                    try (final RevWalk walk = source.walk()) {
                        return walk.isMergedInto(walk.parseCommit(base), walk.parseCommit(tip));
                    }
                }));
    }
}
