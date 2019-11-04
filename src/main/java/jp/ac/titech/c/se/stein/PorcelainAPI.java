package jp.ac.titech.c.se.stein;

import org.eclipse.jgit.api.CleanCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;

public class PorcelainAPI implements AutoCloseable {

    private final Git git;

    public PorcelainAPI(final Repository repo) {
        this.git = new Git(repo);
    }

    @Override
    public void close() {
        git.close();
    }

    /**
     * Applies `git reset --hard (HEAD)`.
     */
    public void resetHard() {
        try {
            final ResetCommand cmd = git.reset();
            cmd.setMode(ResetType.HARD);
            cmd.call();
        } catch (final GitAPIException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Applies `git clean -fd`.
     */
    public void clean() {
        try {
            final CleanCommand cmd = git.clean();
            cmd.setForce(true);
            cmd.setCleanDirectories(true);
            cmd.call();
        } catch (final GitAPIException e) {
            throw new RuntimeException(e);
        }
    }
}
