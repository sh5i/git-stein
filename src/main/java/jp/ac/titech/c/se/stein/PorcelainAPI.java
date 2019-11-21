package jp.ac.titech.c.se.stein;

import org.eclipse.jgit.api.CleanCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.lib.Repository;

import jp.ac.titech.c.se.stein.core.Try;

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
        Try.run(() -> {
            final ResetCommand cmd = git.reset();
            cmd.setMode(ResetType.HARD);
            cmd.call();
        });
    }

    /**
     * Applies `git clean -fd`.
     */
    public void clean() {
        Try.run(() -> {
            final CleanCommand cmd = git.clean();
            cmd.setForce(true);
            cmd.setCleanDirectories(true);
            cmd.call();
        });
    }
}
