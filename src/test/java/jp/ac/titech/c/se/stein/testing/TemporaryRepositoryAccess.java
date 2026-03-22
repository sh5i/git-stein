package jp.ac.titech.c.se.stein.testing;

import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.util.TemporaryFile;
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
