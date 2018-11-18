package jp.ac.titech.c.se.stein;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.Try.ThrowableFunction;

public class ConcurrentRepositoryRewriter extends RepositoryRewriter {
    private static final Logger log = LoggerFactory.getLogger(ConcurrentRepositoryRewriter.class);

    protected boolean concurrent = false;

    protected Map<Thread, ObjectInserter> inserters = null;

    public void setConcurrent(final boolean concurrent) {
        log.debug("Set concurrent: {}", concurrent);
        this.concurrent = concurrent;
        this.entryMapping = concurrent ? new ConcurrentHashMap<>() : new HashMap<>();
    }

    /**
     * Rewrites all commits.
     */
    @Override
    protected void rewriteCommits() {
        if (concurrent) {
            rewriteTreesConcurrently();
        }
        super.rewriteCommits();
    }

    /**
     * Rewrites all trees concurrently.
     */
    protected void rewriteTreesConcurrently() {
        inserters = new ConcurrentHashMap<>();

        try (final RevWalk walk = prepareRevisionWalk()) {
            final Stream<RevCommit> stream = StreamSupport.stream(walk.spliterator(), true).parallel();
            stream.forEach(c -> rewriteRootTree(c.getTree().getId()));
        }

        Try.io(() -> {
            for (final ObjectInserter inserter : inserters.values()) {
                inserter.flush();
            }
        });
        inserters = null;
    }

    @Override
    protected <R> R tryInsert(final ThrowableFunction<ObjectInserter, R> f) {
        if (inserters != null) {
            final Thread thread = Thread.currentThread();
            final ObjectInserter ins = inserters.computeIfAbsent(thread, (t) -> repo.newObjectInserter());
            return Try.io(f).apply(ins);
        } else {
            return super.tryInsert(f);
        }
    }
}
