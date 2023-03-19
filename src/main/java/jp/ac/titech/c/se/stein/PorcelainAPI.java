package jp.ac.titech.c.se.stein;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.internal.storage.file.GC;

import jp.ac.titech.c.se.stein.core.Try;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

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
        Try.run(() -> {
            new GC(repo).repack();
            // delete unnecessary objects/xx directories
            try (final Stream<Path> paths = Files.list(repo.getObjectsDirectory().toPath())) {
                paths.filter(Files::isDirectory).forEach(p -> p.toFile().delete());
            }
        });
    }
}
