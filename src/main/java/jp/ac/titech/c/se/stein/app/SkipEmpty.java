package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.List;

/**
 * Prunes empty commits: a commit whose tree equals its first parent's tree is elided (mapped onto that parent),
 * so a chain of such commits collapses. This is the generic prune-empty that turns a content filter into a
 * filter-repo-style pass when composed (e.g. {@code @subdir X @skip-empty}).
 * Root commits and genuine (multi-parent) merges are kept.
 *
 * Also inspired by preform's {@code EmptyCommitRemover}
 * (<a href="https://github.com/xecua/preform/blob/main/src/main/kotlin/page/caffeine/preform/filter/restructurer/EmptyCommitRemover.kt">source</a>).
 */
@Slf4j
@ToString
@Command(name = "@skip-empty", description = "Elide commits whose tree is unchanged from the first parent")
public class SkipEmpty extends RepositoryRewriter {
    @Override
    protected ObjectId[] rewriteParents(final ObjectId[] parents, final Context c) {
        // Map each parent through the commit mapping, drop ZERO, and collapse duplicates that
        // elision merged onto the same commit, preserving order.
        final List<ObjectId> result = new ArrayList<>(parents.length);
        for (final ObjectId parent : parents) {
            final ObjectId mapped = commitMapping.get(parent);
            final ObjectId p = mapped != null ? mapped : parent;
            if (!p.equals(ZERO) && !result.contains(p)) {
                result.add(p);
            }
        }
        return result.toArray(new ObjectId[0]);
    }

    @Override
    protected ObjectId rewriteCommit(final RevCommit commit, final Context c) {
        // Check the cheap tree equality first, computing the (eliding) parents only for empty
        // candidates; a kept commit then computes its parents just once, inside super. The guard
        // makes getParent(0) safe and excludes root commits.
        if (commit.getParentCount() >= 1) {
            final ObjectId treeId = resolveRootTree(commit.getTree().getId(), c);
            final ObjectId firstParentTree = rootTreeMapping.get(commit.getParent(0).getTree().getId());
            if (treeId.equals(firstParentTree)) {
                final ObjectId[] parentIds = rewriteParents(commit.getParents(), c);
                // Elide unless this is a real merge whose sides did not collapse onto one commit.
                if (parentIds.length == 1) {
                    final ObjectId oldId = commit.getId().copy();
                    commitMapping.put(oldId, parentIds[0]);
                    log.debug("Elide commit: {} -> {} {}", oldId.name(), parentIds[0].name(), c);
                    return parentIds[0];
                }
            }
        }
        return super.rewriteCommit(commit, c);
    }
}
