package jp.ac.titech.c.se.stein.core.cache;

import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.core.Context;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.app.Identity;
import jp.ac.titech.c.se.stein.app.blob.HistorageViaJDT;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class CacheProviderTest {
    static RepositoryAccess source;

    @BeforeAll
    static void setUp() throws IOException {
        source = TestRepo.createSample(true);
    }

    @AfterAll
    static void tearDown() {
        source.close();
    }

    private Application.Config cacheConfig() {
        final Application.Config config = new Application.Config();
        config.isCachingEnabled = true;
        config.cacheBackend = Application.Config.CacheBackend.mvstore;
        return config;
    }

    private void rewriteWithCache(RepositoryRewriter rewriter, Repository targetRepo) {
        rewriter.setConfig(cacheConfig());
        rewriter.initialize(source.repo, targetRepo);
        rewriter.rewrite(Context.init());
    }

    @Test
    public void testCacheProducesCorrectResult() {
        try (RepositoryAccess target = TestRepo.create(true)) {
            rewriteWithCache(new Identity(), target.repo);
            final List<RevCommit> firstRun = target.collectCommits("refs/heads/main");

            assertTrue(new File(target.repo.getDirectory(), "cache.mv.db").exists());

            rewriteWithCache(new Identity(), target.repo);
            final List<RevCommit> secondRun = target.collectCommits("refs/heads/main");

            assertEquals(firstRun.size(), secondRun.size());
            for (int i = 0; i < firstRun.size(); i++) {
                assertEquals(firstRun.get(i).getId(), secondRun.get(i).getId());
            }
        }
    }

    @Test
    public void testCacheMatchesNonCachedResult() {
        try (RepositoryAccess noCacheResult = TestRepo.rewrite(source, new Identity())) {
            final List<RevCommit> noCacheCommits = noCacheResult.collectCommits("refs/heads/main");

            try (RepositoryAccess target = TestRepo.create(true)) {
                rewriteWithCache(new Identity(), target.repo);
                final List<RevCommit> cachedCommits = target.collectCommits("refs/heads/main");

                assertEquals(noCacheCommits.size(), cachedCommits.size());
                for (int i = 0; i < noCacheCommits.size(); i++) {
                    assertEquals(noCacheCommits.get(i).getId(), cachedCommits.get(i).getId());
                }
            }
        }
    }

    @Test
    public void testCacheWithHistorage() {
        try (RepositoryAccess target = TestRepo.create(true)) {
            rewriteWithCache(new HistorageViaJDT().toRewriter(), target.repo);
            final List<RevCommit> firstRun = target.collectCommits("refs/heads/main");
            assertFalse(firstRun.isEmpty());

            rewriteWithCache(new HistorageViaJDT().toRewriter(), target.repo);
            final List<RevCommit> secondRun = target.collectCommits("refs/heads/main");

            assertEquals(firstRun.size(), secondRun.size());
            for (int i = 0; i < firstRun.size(); i++) {
                assertEquals(firstRun.get(i).getId(), secondRun.get(i).getId());
            }
        }
    }

    @Test
    public void testSecondRunHasZeroTranslations() {
        final AtomicInteger count = new AtomicInteger();
        final BlobTranslator counting = (entry, c) -> {
            count.incrementAndGet();
            return entry;
        };

        try (RepositoryAccess target = TestRepo.create(true)) {
            count.set(0);
            rewriteWithCache(counting.toRewriter(), target.repo);
            assertTrue(count.get() > 0, "First run should translate blobs");

            count.set(0);
            rewriteWithCache(counting.toRewriter(), target.repo);
            assertEquals(0, count.get(),
                    "Second run should have 100% cache hit (0 translations), but got " + count.get());
        }
    }
}
