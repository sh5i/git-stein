package jp.ac.titech.c.se.stein;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.errors.*;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.GC;

import jp.ac.titech.c.se.stein.core.Try;

import java.util.Date;

@Slf4j
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
        try {
            git.checkout()
                .setAllPaths(true)
                .setStartPoint("HEAD")
                .setForced(true)
                .call();
        } catch (final GitAPIException | JGitInternalException e) {
            log.error(e.getMessage(), e);
            log.error("Checkout (by jgit) failed, this may be due to the jgit implementation. Try `git checkout -f` on the generated repository.");
        }
    }

    public void repack() {
        Try.run(() -> git.gc()
                .setAggressive(true)
                .setExpire(new Date())
                .call());
    }
}
