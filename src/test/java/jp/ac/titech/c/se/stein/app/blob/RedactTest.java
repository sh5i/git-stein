package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class RedactTest {
    private final Context c = Context.init();
    private Redact app;

    @BeforeEach
    void setUp() throws IOException {
        final Path rules = Files.createTempFile("rules", ".json");
        Files.writeString(rules, """
                [
                  {"pattern": "hunter2"},
                  {"pattern": "token=\\\\w+", "regex": true, "replacement": "token=REDACTED"}
                ]
                """);
        app = new Redact();
        app.rulesFile = rules.toFile();
        app.setUp(c);
    }

    private String redact(final String name, final String content) {
        final AnyHotEntry out = app.rewriteBlobEntry(HotEntry.ofBlob(name, content), c);
        return ((BlobEntry) out).getContent();
    }

    @Test
    public void testReplacesLiteralWithDefaultAndRegexWithReplacement() {
        assertEquals("password=***REDACTED*** token=REDACTED\n",
                redact("secrets.txt", "password=hunter2 token=abc123\n"));
    }

    @Test
    public void testNonMatchingContentIsUnchanged() {
        final BlobEntry in = HotEntry.ofBlob("readme.txt", "nothing secret here\n");
        assertSame(in, app.rewriteBlobEntry(in, c)); // unchanged blobs are returned as-is
    }

    @Test
    public void testBinaryBlobIsSkipped() {
        final BlobEntry in = HotEntry.ofBlob("data.bin", new byte[]{'h', 'u', 'n', 't', 'e', 'r', '2', 0});
        assertSame(in, app.rewriteBlobEntry(in, c)); // a NUL byte marks it binary; the secret inside is left untouched
    }

    @Test
    public void testNameFilterScopesRedactionToMatchingFiles() {
        app.nameFilter.setPatterns("*.txt");
        // a matching file is redacted; a non-matching one is left untouched
        assertEquals("password=***REDACTED*** token=REDACTED\n", redact("secrets.txt", "password=hunter2 token=abc123\n"));
        assertEquals("password=hunter2 token=abc123\n", redact("config.ini", "password=hunter2 token=abc123\n"));
    }
}
