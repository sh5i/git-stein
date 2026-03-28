package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.app.blob.HistorageViaJDT;
import jp.ac.titech.c.se.stein.app.blob.TokenizeViaJDT;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class BlobTranslatorTest {
    static final Context CTX = Context.init();

    @Test
    public void testOf() {
        final BlobTranslator upper = BlobTranslator.of(String::toUpperCase);
        final AnyHotEntry result = upper.rewriteBlobEntry(HotEntry.ofBlob("f.txt", "hello"), CTX);
        assertEquals("HELLO", result.asBlob().getContent());
    }

    @Test
    public void testSingleCompositeSingle() {
        final BlobTranslator translator = BlobTranslator.composite(BlobTranslator.of(String::toUpperCase));
        final AnyHotEntry result = translator.rewriteBlobEntry(HotEntry.ofBlob("f.txt", "hello"), CTX);
        assertEquals(1, result.size());
        assertEquals("HELLO", result.asBlob().getContent());
    }

    @Test
    public void testCompositeMultiple() {
        final BlobTranslator translator = BlobTranslator.composite(
                BlobTranslator.of(s -> "PREFIX:" + s),
                BlobTranslator.of(String::toUpperCase));
        final AnyHotEntry result = translator.rewriteBlobEntry(HotEntry.ofBlob("f.txt", "hello"), CTX);
        assertEquals(1, result.size());
        assertEquals("PREFIX:HELLO", result.asBlob().getContent());
    }

    @Test
    public void testSplit() {
        final BlobTranslator splitter = (entry, c) -> {
            final AnyHotEntry.Set set = AnyHotEntry.set();
            set.add(entry.rename("a_" + entry.getName()));
            set.add(HotEntry.of(entry.getMode(), "b_" + entry.getName(), entry.getContent() + "_copy"));
            return set;
        };
        final AnyHotEntry result = splitter.rewriteBlobEntry(HotEntry.ofBlob("f.txt", "data"), CTX);
        final List<? extends HotEntry> entries = result.stream().collect(Collectors.toList());
        assertEquals(2, entries.size());
        assertEquals("a_f.txt", entries.get(0).getName());
        assertEquals("b_f.txt", entries.get(1).getName());
    }

    @Test
    public void testFilter() {
        final BlobTranslator filter = (entry, c) ->
                entry.getName().endsWith(".bak") ? AnyHotEntry.empty() : entry;
        assertEquals(0, filter.rewriteBlobEntry(HotEntry.ofBlob("test.bak", "backup"), CTX).size());
        assertEquals(1, filter.rewriteBlobEntry(HotEntry.ofBlob("test.txt", "keep"), CTX).size());
    }

    @Test
    public void testSplitThenTransform() {
        final BlobTranslator splitter = (entry, c) -> {
            final AnyHotEntry.Set set = AnyHotEntry.set();
            set.add(entry);
            set.add(entry.rename("copy_" + entry.getName()));
            return set;
        };
        final BlobTranslator translator = BlobTranslator.composite(splitter, BlobTranslator.of(String::toUpperCase));
        final List<? extends HotEntry> entries = translator
                .rewriteBlobEntry(HotEntry.ofBlob("f.txt", "hello"), CTX)
                .stream().collect(Collectors.toList());
        assertEquals(2, entries.size());
        assertEquals("HELLO", entries.get(0).asBlob().getContent());
        assertEquals("HELLO", entries.get(1).asBlob().getContent());
    }

    @Test
    public void testFinerGit() throws IOException {
        try (RepositoryAccess source = TestRepo.createSample()) {
            final BlobTranslator composite = BlobTranslator.composite(new HistorageViaJDT(), new TokenizeViaJDT());

            try (RepositoryAccess compositeResult = TestRepo.rewrite(source, composite);
                 RepositoryAccess step1 = TestRepo.rewrite(source, new HistorageViaJDT());
                 RepositoryAccess sequentialResult = TestRepo.rewrite(step1, new TokenizeViaJDT())) {

                final RevCommit compositeHead = compositeResult.getHead("refs/heads/main");
                final RevCommit sequentialHead = sequentialResult.getHead("refs/heads/main");

                final List<Entry> compositeFiles = compositeResult.flattenTree(compositeHead.getTree().getId());
                final List<Entry> sequentialFiles = sequentialResult.flattenTree(sequentialHead.getTree().getId());

                assertEquals(
                        compositeFiles.stream().map(Entry::getName).sorted().collect(Collectors.toList()),
                        sequentialFiles.stream().map(Entry::getName).sorted().collect(Collectors.toList()));

                for (Entry ce : compositeFiles) {
                    final Entry se = sequentialFiles.stream()
                            .filter(e -> e.getName().equals(ce.getName()))
                            .findFirst().orElseThrow();
                    assertEquals(ce.getId(), se.getId(), "blob mismatch for " + ce.getName());
                }
            }
        }
    }
}
