package jp.ac.titech.c.se.stein;

import org.eclipse.jgit.api.Git;
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
            git.reset()
                .setMode(ResetType.HARD)
                .call();
        });
    }

    /**
     * Applies `git clean -fd`.
     */
    public void clean() {
        Try.run(() -> {
            git.clean()
                .setForce(true)
                .setCleanDirectories(true)
                .call();
        });
    }

    public void checkout() {
        Try.run(() -> {
            git.checkout()
                .setAllPaths(true)
                .setStartPoint("HEAD")
                .call();
        });
    }
}
