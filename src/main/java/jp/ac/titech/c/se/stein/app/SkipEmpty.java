package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.rewriter.CommitDropper;
import lombok.ToString;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import picocli.CommandLine.Command;

/**
 * Prunes empty commits: a commit whose tree equals its first parent's tree is dropped (its children splice onto that parent),
 * so a chain of such commits collapses. This is the generic prune-empty that turns a content filter into a
 * filter-repo-style pass when composed (e.g. {@code @subdir X @skip-empty}).
 * Root commits and genuine (multi-parent) merges are kept.
 *
 * Also inspired by preform's {@code EmptyCommitRemover}
 * (<a href="https://github.com/xecua/preform/blob/main/src/main/kotlin/page/caffeine/preform/filter/restructurer/EmptyCommitRemover.kt">source</a>).
 */
@ToString
@Command(name = "@skip-empty", description = "Drop commits whose tree is unchanged from the first parent")
public class SkipEmpty extends CommitDropper {
    @Override
    protected boolean shouldDrop(final RevCommit commit, final ObjectId treeId, final ObjectId[] parentIds, final Context c) {
        // One spliced parent means a non-merge commit (or a merge whose sides collapsed onto a
        // single commit); drop it when it added nothing to that parent. Genuine merges are kept.
        return parentIds.length == 1
                && treeId.equals(rootTreeMapping.get(commit.getParent(0).getTree().getId()));
    }
}
