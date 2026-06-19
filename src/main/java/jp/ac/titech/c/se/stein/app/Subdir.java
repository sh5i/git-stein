package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import lombok.ToString;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Collections;

/**
 * Re-roots the history at a subdirectory: each commit's tree is replaced by the tree at the given path,
 * like filter-repo's {@code --subdirectory-filter}. The empty-commit pruning is intentionally
 * left to a composed {@code @skip-empty}. A commit whose tree lacks the path becomes empty.
 */
@ToString
@Command(name = "@subdir", description = "Re-root the history at a subdirectory")
public class Subdir extends RepositoryRewriter {
    @Option(names = {"-p", "--path"}, paramLabel = "<path>", description = "the subdirectory to become the new root", required = true)
    protected String path;

    @Override
    protected ObjectId rewriteRootTree(final ObjectId treeId, final Context c) {
        final ObjectId sub = source.lookup(treeId, path);
        return sub != null && source.getObjectType(sub) == Constants.OBJ_TREE
                ? super.rewriteRootTree(sub, c)
                : target.writeTree(Collections.emptyList(), c);
    }
}
