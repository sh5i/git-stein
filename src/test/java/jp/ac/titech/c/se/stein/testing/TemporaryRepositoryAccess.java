package jp.ac.titech.c.se.stein.testing;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import jp.ac.titech.c.se.stein.util.TemporaryFile;
import org.eclipse.jgit.internal.storage.dfs.DfsRepositoryDescription;
import org.eclipse.jgit.internal.storage.dfs.InMemoryRepository;
import org.eclipse.jgit.lib.Repository;

import java.io.IOException;

/**
 * A {@link RepositoryAccess} that owns its repository and optionally a temporary directory,
 * cleaning up both on {@link #close()}.
 */
public class TemporaryRepositoryAccess extends RepositoryAccess {
    private final TemporaryFile.Directory tmpDir;

    public TemporaryRepositoryAccess(Repository repo) {
        this(repo, null);
    }

    public TemporaryRepositoryAccess(Repository repo, TemporaryFile.Directory tmpDir) {
        super(repo);
        this.tmpDir = tmpDir;
    }

    public TemporaryRepositoryAccess rewrite(RepositoryRewriter rewriter) {
        final Repository targetRepo = new InMemoryRepository(new DfsRepositoryDescription("target"));
        rewriter.setConfig(new Application.Config());
        rewriter.initialize(repo, targetRepo);
        rewriter.rewrite(Context.init());
        return new TemporaryRepositoryAccess(targetRepo);
    }

    public TemporaryRepositoryAccess rewrite(BlobTranslator translator) {
        return rewrite(translator.create());
    }

    @Override
    public void close() {
        super.close();
        if (tmpDir != null) {
            try {
                tmpDir.close();
            } catch (IOException e) {
                // ignore
            }
        }
    }
}
