package jp.ac.titech.c.se.stein.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class CommitHeadersTest {
    static final String GPGSIG_VALUE = "-----BEGIN PGP SIGNATURE-----\n wsBcBAABCAAQBQ==\n -----END PGP SIGNATURE-----\n";
    static final String SAMPLE = "encoding ISO-8859-1\n"
            + "gpgsig " + GPGSIG_VALUE
            + "change-id Iabc123def\n";

    static byte[] sample() {
        return SAMPLE.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    public void testPassThroughNoParse() {
        // Identity: toBytes returns the same byte[] reference when untouched
        final byte[] raw = sample();
        final CommitHeaders headers = new CommitHeaders(raw);
        assertSame(raw, headers.toBytes());
    }

    @Test
    public void testEmpty() {
        assertEquals(0, new CommitHeaders(null).toBytes().length);
        assertEquals(0, new CommitHeaders(new byte[0]).toBytes().length);
    }

    @Test
    public void testGet() {
        final CommitHeaders headers = new CommitHeaders(sample());
        assertArrayEquals("ISO-8859-1\n".getBytes(StandardCharsets.US_ASCII), headers.get("encoding"));
        assertArrayEquals(GPGSIG_VALUE.getBytes(StandardCharsets.US_ASCII), headers.get("gpgsig"));
        assertArrayEquals("Iabc123def\n".getBytes(StandardCharsets.US_ASCII), headers.get("change-id"));
        assertNull(headers.get("nonexistent"));
    }

    @Test
    public void testContainsAndEntries() {
        final CommitHeaders headers = new CommitHeaders(sample());
        assertTrue(headers.contains("gpgsig"));
        assertFalse(headers.contains("mergetag"));
        final List<Map.Entry<String, byte[]>> entries = headers.entries();
        assertEquals(3, entries.size());
        assertEquals("encoding", entries.get(0).getKey());
        assertEquals("gpgsig", entries.get(1).getKey());
        assertEquals("change-id", entries.get(2).getKey());
    }

    @Test
    public void testReadOnlyDoesNotMutate() {
        // Reading via get/contains/entries should not cause re-serialization on toBytes
        final byte[] raw = sample();
        final CommitHeaders headers = new CommitHeaders(raw);
        headers.get("gpgsig");
        headers.contains("change-id");
        headers.entries();
        assertSame(raw, headers.toBytes());
    }

    @Test
    public void testRemove() {
        final CommitHeaders headers = new CommitHeaders(sample());
        headers.remove("gpgsig");
        final String result = new String(headers.toBytes(), StandardCharsets.US_ASCII);
        assertEquals("encoding ISO-8859-1\nchange-id Iabc123def\n", result);
    }

    @Test
    public void testSetReplaces() {
        final CommitHeaders headers = new CommitHeaders(sample());
        headers.set("change-id", "Inew456\n".getBytes(StandardCharsets.US_ASCII));
        final String result = new String(headers.toBytes(), StandardCharsets.US_ASCII);
        assertEquals("encoding ISO-8859-1\ngpgsig " + GPGSIG_VALUE + "change-id Inew456\n", result);
    }

    @Test
    public void testSetWhenAbsentAppends() {
        final CommitHeaders headers = new CommitHeaders(sample());
        headers.set("mergetag", "object abc\ntype commit\n".getBytes(StandardCharsets.US_ASCII));
        final List<Map.Entry<String, byte[]>> entries = headers.entries();
        assertEquals(4, entries.size());
        assertEquals("mergetag", entries.get(3).getKey());
    }

    @Test
    public void testAdd() {
        final CommitHeaders headers = new CommitHeaders(sample());
        headers.add("x-custom", "value-1\n".getBytes(StandardCharsets.US_ASCII));
        headers.add("x-custom", "value-2\n".getBytes(StandardCharsets.US_ASCII));
        final List<byte[]> all = headers.getAll("x-custom");
        assertEquals(2, all.size());
        assertEquals("value-1\n", new String(all.get(0), StandardCharsets.US_ASCII));
        assertEquals("value-2\n", new String(all.get(1), StandardCharsets.US_ASCII));
    }

    @Test
    public void testGetAllMultipleOccurrences() {
        final byte[] raw = "mergetag tag1-data\nmergetag tag2-data\n".getBytes(StandardCharsets.US_ASCII);
        final CommitHeaders headers = new CommitHeaders(raw);
        final List<byte[]> all = headers.getAll("mergetag");
        assertEquals(2, all.size());
        assertEquals("tag1-data\n", new String(all.get(0), StandardCharsets.US_ASCII));
        assertEquals("tag2-data\n", new String(all.get(1), StandardCharsets.US_ASCII));
    }

    @Test
    public void testRoundTripAfterParseWithoutMutation() {
        // Even after parsing via get(), if not mutated, toBytes returns the original raw
        final byte[] raw = sample();
        final CommitHeaders headers = new CommitHeaders(raw);
        headers.get("gpgsig");
        assertArrayEquals(raw, headers.toBytes());
    }

    @Test
    public void testRoundTripAfterIdempotentSet() {
        // Setting a header to the same value still re-serializes, and yields identical bytes
        final byte[] raw = sample();
        final CommitHeaders headers = new CommitHeaders(raw);
        headers.set("encoding", "ISO-8859-1\n".getBytes(StandardCharsets.US_ASCII));
        assertArrayEquals(raw, headers.toBytes());
    }

    @Test
    public void testTrailingNewlineGuard() {
        // set with a value missing trailing \n still produces a valid header
        final CommitHeaders headers = new CommitHeaders(new byte[0]);
        headers.set("foo", "bar".getBytes(StandardCharsets.US_ASCII));
        assertEquals("foo bar\n", new String(headers.toBytes(), StandardCharsets.US_ASCII));
    }

    @Test
    public void testFoldUnfold() {
        // Single-line: trailing LF stripped, no continuation markers
        assertEquals("Iabc123def",
                CommitHeaders.asString("Iabc123def\n".getBytes(StandardCharsets.US_ASCII), StandardCharsets.US_ASCII));

        // Multi-line (gpgsig): both directions against an explicit oracle.
        // These two assertions together imply round-trip identity in either direction.
        final byte[] folded = GPGSIG_VALUE.getBytes(StandardCharsets.US_ASCII);
        final String logical = "-----BEGIN PGP SIGNATURE-----\nwsBcBAABCAAQBQ==\n-----END PGP SIGNATURE-----";
        assertEquals(logical, CommitHeaders.asString(folded, StandardCharsets.US_ASCII));
        assertArrayEquals(folded, CommitHeaders.fromString(logical, StandardCharsets.US_ASCII));
    }

    @Test
    public void testFoldUnfoldRoundTrip() {
        // Round-trip on tricky inputs not covered by the oracle test above:
        // blank lines, trailing whitespace, and non-ASCII via the UTF-8 default overloads.
        final String logical = "line1\n\nline3 with trailing space \nlast";
        assertEquals(logical, CommitHeaders.asString(CommitHeaders.fromString(logical, StandardCharsets.US_ASCII), StandardCharsets.US_ASCII));

        final String utf8 = "日本語\nsecond line";
        assertArrayEquals(CommitHeaders.fromString(utf8, StandardCharsets.UTF_8), CommitHeaders.fromString(utf8));
        assertEquals(utf8, CommitHeaders.asString(CommitHeaders.fromString(utf8)));
    }
}
