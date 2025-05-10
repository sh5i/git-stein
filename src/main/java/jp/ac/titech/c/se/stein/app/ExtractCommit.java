package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RefEntry;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.core.Try;
import jp.ac.titech.c.se.stein.jgit.RevWalk;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Slf4j
@ToString
@Command(name = "@extract-commit", description = "Extract a specific commit")
public class ExtractCommit extends RepositoryRewriter {
    @Option(names = "--target", paramLabel = "<commit>", description = "Target commit",
            required = true)
    protected String targetCommitSpec;

    protected RevCommit targetCommit, parentCommit;

    @Override
    protected RevWalk prepareRevisionWalk(final Context c) {
        final RevWalk walk = source.walk();
        Try.io(c, () -> {
            targetCommit = walk.parseCommit(source.resolve(targetCommitSpec));
            walk.memoMarkStart(targetCommit);
            if (targetCommit.getParentCount() > 0) {
                // mark non-first parents as uninteresting
                for (int i = 1; i < targetCommit.getParentCount(); i++) {
                    walk.memoMarkUninteresting(targetCommit.getParent(i));
                }
                // mark ancestors as uninteresting
                parentCommit = walk.parseCommit(targetCommit.getParent(0));
                if (parentCommit.getParentCount() > 0) {
                    for (final RevCommit pp : parentCommit.getParents()) {
                        walk.memoMarkUninteresting(pp);
                    }
                }
            }
        });
        return walk;
    }

    @Override
    protected ObjectId[] rewriteParents(final ObjectId[] parents, final Context c) {
        // kill non-first parents
        if (c.getRev().equals(targetCommit)) {
            ObjectId[] updatedParents = parents.length > 1 ? new ObjectId[] { parents[0] } : parents;
            return super.rewriteParents(updatedParents, c);
        }
        // stop at the parent
        if (c.getRev().equals(parentCommit)) {
            return RepositoryAccess.NO_PARENTS;
        }
        return super.rewriteParents(parents, c);
    }

    @Override
    protected void updateRefs(final Context c) {
        // only the main branch, pointed by HEAD
        target.applyRefUpdate(new RefEntry("refs/heads/main", commitMapping.get(targetCommit.getId())));
        target.applyRefUpdate(new RefEntry("HEAD", "refs/heads/main"));
    }
}
