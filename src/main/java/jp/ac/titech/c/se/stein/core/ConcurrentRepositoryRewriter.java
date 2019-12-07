package jp.ac.titech.c.se.stein.core;

import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.core.Context.Key;

public class ConcurrentRepositoryRewriter extends RepositoryRewriter implements Configurable {
    private static final Logger log = LoggerFactory.getLogger(ConcurrentRepositoryRewriter.class);

    protected boolean concurrent = false;

    public void setConcurrent(final boolean concurrent) {
        log.debug("Set concurrent: {}", concurrent);
        this.concurrent = concurrent;
        this.entryMapping = concurrent ? new ConcurrentHashMap<>() : new HashMap<>();
        log.debug("Concurrent mode: {}", concurrent);
    }

    @Override
    public void addOptions(final Config conf) {
        super.addOptions(conf);
        conf.addOption("c", "concurrent", false, "rewrite trees concurrently");
    }

    @Override
    public void configure(final Config conf) {
        super.configure(conf);
        if (conf.hasOption("concurrent")) {
            setConcurrent(true);
        }
    }

    /**
     * Rewrites all commits.
     */
    @Override
    protected void rewriteCommits(final Context c) {
        if (concurrent) {
            rewriteTreesConcurrently(c);
        }
        super.rewriteCommits(c);
    }

    /**
     * Rewrites all trees concurrently.
     */
    protected void rewriteTreesConcurrently(final Context c) {
        final ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
        try (final RevWalk walk = prepareRevisionWalk(c)) {
            for (final RevCommit commit : walk) {
                pool.execute(() -> {
                    try (final ObjectInserter ins = writeRepo.newObjectInserter()) {
                        final Context uc = c.with(Key.rev, commit, Key.commit, commit, Key.inserter, ins);
                        rewriteRootTree(commit.getTree().getId(), uc);
                    }
                });
            }
        }
        pool.shutdown();
        Try.run(() -> pool.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS));
    }
}
