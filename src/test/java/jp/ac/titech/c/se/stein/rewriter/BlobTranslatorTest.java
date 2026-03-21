package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.app.blob.HistorageViaJDT;
import jp.ac.titech.c.se.stein.app.blob.TokenizeViaJDT;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class BlobTranslatorTest {
    static final int BLOB_MODE = FileMode.REGULAR_FILE.getBits();
    static final Context CTX = Context.init();

    HotEntry blob(String name, String content) {
        return HotEntry.of(BLOB_MODE, name, content.getBytes(StandardCharsets.UTF_8));
    }

    String content(HotEntry entry) {
        return new String(entry.getBlob(), StandardCharsets.UTF_8);
    }

    @Test
    public void testOf() {
        final BlobTranslator upper = BlobTranslator.of(String::toUpperCase);
        final AnyHotEntry result = upper.rewriteBlobEntry(blob("f.txt", "hello"), CTX);
        assertEquals("HELLO", content(result.stream().findFirst().orElseThrow()));
    }

    @Test
    public void testSingleCompositeSingle() {
        final RepositoryRewriter translator = new BlobTranslator.Composite(
                BlobTranslator.of(String::toUpperCase));
        final AnyHotEntry result = translator.rewriteBlobEntry(blob("f.txt", "hello"), CTX);
        assertEquals(1, result.size());
        assertEquals("HELLO", content(result.stream().findFirst().orElseThrow()));
    }

    @Test
    public void testCompositeMultiple() {
        final RepositoryRewriter translator = new BlobTranslator.Composite(
                BlobTranslator.of(s -> "PREFIX:" + s),
                BlobTranslator.of(String::toUpperCase));
        final AnyHotEntry result = translator.rewriteBlobEntry(blob("f.txt", "hello"), CTX);
        assertEquals(1, result.size());
        assertEquals("PREFIX:HELLO", content(result.stream().findFirst().orElseThrow()));
    }

    @Test
    public void testSplit() {
        final BlobTranslator splitter = (entry, c) -> {
            final AnyHotEntry.Set set = AnyHotEntry.set();
            set.add(entry.rename("a_" + entry.getName()));
            set.add(HotEntry.of(entry.getMode(), "b_" + entry.getName(),
                    (content(entry) + "_copy").getBytes(StandardCharsets.UTF_8)));
            return set;
        };
        final AnyHotEntry result = splitter.rewriteBlobEntry(blob("f.txt", "data"), CTX);
        final List<? extends HotEntry> entries = result.stream().collect(Collectors.toList());
        assertEquals(2, entries.size());
        assertEquals("a_f.txt", entries.get(0).getName());
        assertEquals("b_f.txt", entries.get(1).getName());
    }

    @Test
    public void testFilter() {
        final BlobTranslator filter = (entry, c) ->
                entry.getName().endsWith(".bak") ? AnyHotEntry.empty() : entry;
        assertEquals(0, filter.rewriteBlobEntry(blob("test.bak", "backup"), CTX).size());
        assertEquals(1, filter.rewriteBlobEntry(blob("test.txt", "keep"), CTX).size());
    }

    @Test
    public void testSplitThenTransform() {
        final BlobTranslator splitter = (entry, c) -> {
            final AnyHotEntry.Set set = AnyHotEntry.set();
            set.add(entry);
            set.add(entry.rename("copy_" + entry.getName()));
            return set;
        };
        final RepositoryRewriter translator = new BlobTranslator.Composite(
                splitter, BlobTranslator.of(String::toUpperCase));
        final List<? extends HotEntry> entries = translator
                .rewriteBlobEntry(blob("f.txt", "hello"), CTX)
                .stream().collect(Collectors.toList());
        assertEquals(2, entries.size());
        assertEquals("HELLO", content(entries.get(0)));
        assertEquals("HELLO", content(entries.get(1)));
    }

    @Test
    public void testFinerGit() throws IOException {
        try (TestRepo source = TestRepo.create()) {
            final RepositoryRewriter composite =
                    new BlobTranslator.Composite(new HistorageViaJDT(), new TokenizeViaJDT());

            try (TestRepo.RewriteResult compositeResult = source.rewrite(composite);
                 TestRepo.RewriteResult step1 = source.rewrite(new HistorageViaJDT());
                 TestRepo.RewriteResult sequentialResult = step1.rewrite(new TokenizeViaJDT())) {

                final RevCommit compositeHead = compositeResult.access.getHead("refs/heads/main");
                final RevCommit sequentialHead = sequentialResult.access.getHead("refs/heads/main");

                final List<Entry> compositeFiles = compositeResult.access.flattenTree(compositeHead.getTree().getId());
                final List<Entry> sequentialFiles = sequentialResult.access.flattenTree(sequentialHead.getTree().getId());

                assertEquals(
                        compositeFiles.stream().map(Entry::getName).sorted().collect(Collectors.toList()),
                        sequentialFiles.stream().map(Entry::getName).sorted().collect(Collectors.toList()));

                for (Entry ce : compositeFiles) {
                    Entry se = sequentialFiles.stream()
                            .filter(e -> e.getName().equals(ce.getName()))
                            .findFirst().orElseThrow();
                    assertEquals(ce.getId(), se.getId(), "blob mismatch for " + ce.getName());
                }
            }
        }
    }

}
