package jp.ac.titech.c.se.stein;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.GC;

import jp.ac.titech.c.se.stein.core.Try;

import java.util.Date;

public class PorcelainAPI implements AutoCloseable {
    private final FileRepository repo;

    private final Git git;

    public PorcelainAPI(final FileRepository repo) {
        this.repo = repo;
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

    public void repack() {
        Try.run(() -> git.gc()
                .setAggressive(true)
                .setExpire(new Date())
                .call());
    }
}
