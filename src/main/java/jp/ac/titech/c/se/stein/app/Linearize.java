package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import lombok.ToString;
import org.eclipse.jgit.lib.ObjectId;
import picocli.CommandLine.Command;

/**
 * Linearizes the history by keeping only each commit's first parent, so every ref follows its
 * first-parent mainline. Side branches merged in via second (and later) parents become
 * unreferenced and drop out of the rewritten refs.
 */
@ToString
@Command(name = "@linearize", description = "Keep only the first-parent mainline of each ref")
public class Linearize extends RepositoryRewriter {
    @Override
    protected ObjectId[] rewriteParents(final ObjectId[] parents, final Context c) {
        final ObjectId[] firstOnly = parents.length <= 1 ? parents : new ObjectId[]{parents[0]};
        return super.rewriteParents(firstOnly, c);
    }
}
