package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.app.Identity;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RefEntry;
import jp.ac.titech.c.se.stein.core.RefNamespace;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link RepositoryRewriter}.
 */
public class RepositoryRewriterTest {
    private static final RefNamespace STAGING = new RefNamespace("refs/namespaces/git-stein.1/");

    private static void rewriteIntoStaging(final RepositoryRewriter rewriter, final RepositoryAccess ra) {
        rewriter.setConfig(new Application.Config());
        rewriter.initialize(ra.repo, RefNamespace.ROOT, ra.repo, STAGING);
        rewriter.useDefaultScope();
        rewriter.rewrite(Context.init());
    }

    @Test
    public void testStagingIsIncrementalAndPrunesObsoleteRefs() throws IOException {
        try (RepositoryAccess ra = TestRepo.createSample()) {
            final AtomicInteger translations = new AtomicInteger();
            final BlobTranslator counting = (entry, c) -> {
                translations.incrementAndGet();
                return entry;
            };

            // first run populates the staging namespace
            translations.set(0);
            rewriteIntoStaging(counting.toRewriter(), ra);
            assertTrue(translations.get() > 0, "First run should translate blobs");

            // inject an obsolete staging head, as if left over from a previous run
            final RepositoryAccess staged = new RepositoryAccess(ra.repo, STAGING);
            final ObjectId stagedMain = staged.getRef("refs/heads/main").id;
            staged.applyRefUpdate(RefEntry.of("refs/heads/ghost", stagedMain));

            // second run: no new commits, so the boundary restored from staging notes makes it
            // incremental (zero translations) even though the obsolete head is pruned
            translations.set(0);
            rewriteIntoStaging(counting.toRewriter(), ra);
            assertEquals(0, translations.get(),
                    "Second run should reuse previous tips; pruning must not drop the incremental notes");
            assertNull(staged.getRef("refs/heads/ghost"), "Obsolete staging head should be pruned");
            assertEquals(stagedMain, staged.getRef("refs/heads/main").id, "Live head should remain");
        }
    }

    @Test
    public void testInPlaceRewriteSkipsNotesAndDoesNotThrow() throws IOException {
        try (RepositoryAccess ra = TestRepo.createSample()) {
            final RepositoryRewriter rewriter = new Identity();
            rewriter.setConfig(new Application.Config());   // notes enabled by default
            rewriter.initialize(ra.repo, RefNamespace.ROOT, ra.repo, RefNamespace.ROOT);   // in-place
            rewriter.useDefaultScope();
            rewriter.rewrite(Context.init());               // must not throw (no null prevNotes)
            // an in-place rewrite does not track notes, so no notes ref is written
            assertNull(ra.repo.getRefDatabase().exactRef("refs/notes/commits"));
        }
    }

    @Test
    public void testNoNotesWritesNoNotesRef() throws IOException {
        try (RepositoryAccess src = TestRepo.createSample();
             RepositoryAccess dst = TestRepo.create()) {
            final RepositoryRewriter rewriter = new Identity();
            final Application.Config config = new Application.Config();
            config.isAddingNotes = false;
            rewriter.setConfig(config);
            rewriter.initialize(src.repo, dst.repo);
            rewriter.useDefaultScope();
            rewriter.rewrite(Context.init());
            // --no-notes must not create a spurious notes ref
            assertNull(dst.repo.getRefDatabase().exactRef("refs/notes/commits"));
        }
    }

    @Test
    public void testDryRunWritesNothingAndDoesNotThrow() throws IOException {
        try (RepositoryAccess src = TestRepo.createSample();
             RepositoryAccess dst = TestRepo.create()) {
            final RepositoryRewriter rewriter = new Identity();
            final Application.Config config = new Application.Config();
            config.isDryRunning = true;                     // notes stay enabled
            rewriter.setConfig(config);
            rewriter.initialize(src.repo, dst.repo);
            rewriter.useDefaultScope();
            // a dry run writes no ref, so nothing it wrote can be read back
            rewriter.rewrite(Context.init());
            assertTrue(dst.getRefs().isEmpty());
            assertNull(dst.repo.getRefDatabase().exactRef("refs/notes/commits"));
        }
    }
}
