package jp.ac.titech.c.se.stein.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.util.RawParseUtils;

/**
 * Represents the "extra headers" section of a commit object — the bytes between the
 * committer line and the blank line separating headers from the message. This covers standard
 * headers ({@code encoding}, {@code gpgsig}) and arbitrary application-defined ones
 * ({@code change-id}, {@code mergetag}, ...) that {@link org.eclipse.jgit.lib.CommitBuilder}
 * cannot represent.
 *
 * <p>The class wraps the original raw bytes and parses them lazily on first access. As long as
 * no mutator is invoked, the original bytes are returned verbatim, so identity is byte-perfect.</p>
 *
 * <p>Header values are exposed as raw (folded) bytes, including continuation-line markers and
 * the trailing {@code LF}. The String conversions translate between this raw form and the
 * logical (unfolded) form.</p>
 */
public class CommitHeaders {
    private final byte[] raw;
    private List<Map.Entry<String, byte[]>> entries;
    private boolean mutated = false;

    /**
     * Wraps the given raw bytes. {@code null} is treated as empty.
     */
    public CommitHeaders(final byte[] raw) {
        this.raw = raw != null ? raw : new byte[0];
    }

    /**
     * Returns the serialized bytes. If no mutation occurred, the original raw bytes are
     * returned verbatim (byte-perfect round-trip).
     */
    public byte[] toBytes() {
        return mutated ? join(entries) : raw;
    }

    /**
     * Returns the value of the first header with the given name, or {@code null} if absent.
     * The returned bytes include any continuation-line markers and the trailing {@code LF}.
     */
    public byte[] get(final String name) {
        ensureParsed();
        for (final Map.Entry<String, byte[]> e : entries) {
            if (e.getKey().equals(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /**
     * Returns all values of headers with the given name, in order. Multi-occurrence headers
     * (e.g., {@code mergetag} in octopus merges) yield more than one entry.
     */
    public List<byte[]> getAll(final String name) {
        ensureParsed();
        final List<byte[]> result = new ArrayList<>();
        for (final Map.Entry<String, byte[]> e : entries) {
            if (e.getKey().equals(name)) {
                result.add(e.getValue());
            }
        }
        return result;
    }

    /**
     * Returns whether any header with the given name is present.
     */
    public boolean contains(final String name) {
        ensureParsed();
        for (final Map.Entry<String, byte[]> e : entries) {
            if (e.getKey().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a read-only snapshot of all entries in declaration order.
     */
    public List<Map.Entry<String, byte[]>> entries() {
        ensureParsed();
        return Collections.unmodifiableList(entries);
    }

    /**
     * Replaces the first header with the given name. If absent, the header is appended.
     * All later occurrences with the same name are removed.
     */
    public void set(final String name, final byte[] value) {
        ensureParsed();
        boolean replaced = false;
        final List<Map.Entry<String, byte[]>> result = new ArrayList<>(entries.size());
        for (final Map.Entry<String, byte[]> e : entries) {
            if (e.getKey().equals(name)) {
                if (!replaced) {
                    result.add(Map.entry(name, value));
                    replaced = true;
                }
            } else {
                result.add(e);
            }
        }
        if (!replaced) {
            result.add(Map.entry(name, value));
        }
        entries = result;
        mutated = true;
    }

    /**
     * Removes all headers with the given name. No-op if absent.
     */
    public void remove(final String name) {
        ensureParsed();
        entries.removeIf(e -> e.getKey().equals(name));
        mutated = true;
    }

    /**
     * Appends a new header at the end. Does not affect existing headers with the same name.
     */
    public void add(final String name, final byte[] value) {
        ensureParsed();
        entries.add(Map.entry(name, value));
        mutated = true;
    }

    /**
     * Decodes a raw (folded) value into its logical String form. Continuation-line markers are
     * removed (each {@code LF SP} becomes {@code LF}) and the trailing {@code LF} that terminates
     * the header block is stripped. The inverse of {@link #fromString}.
     */
    public static String asString(final byte[] value, final Charset enc) {
        String s = new String(value, enc);
        if (s.endsWith("\n")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.replace("\n ", "\n");
    }

    /**
     * UTF-8 variant of {@link #asString(byte[], Charset)}.
     */
    public static String asString(final byte[] value) {
        return asString(value, StandardCharsets.UTF_8);
    }

    /**
     * Encodes a logical String value into its raw (folded) form suitable for storage. Each
     * internal {@code LF} is folded into {@code LF SP} (continuation marker) and a terminating
     * {@code LF} is appended. The inverse of {@link #asString}.
     */
    public static byte[] fromString(final String value, final Charset enc) {
        return (value.replace("\n", "\n ") + "\n").getBytes(enc);
    }

    /**
     * UTF-8 variant of {@link #fromString(String, Charset)}.
     */
    public static byte[] fromString(final String value) {
        return fromString(value, StandardCharsets.UTF_8);
    }

    private void ensureParsed() {
        if (entries == null) {
            entries = split(raw);
        }
    }

    private static List<Map.Entry<String, byte[]>> split(final byte[] raw) {
        final List<Map.Entry<String, byte[]>> result = new ArrayList<>();
        final int n = raw.length;
        int i = 0;
        while (i < n) {
            int keyEnd = i;
            while (keyEnd < n && raw[keyEnd] != ' ') {
                keyEnd++;
            }
            if (keyEnd >= n) {
                break;
            }
            final String key = new String(raw, i, keyEnd - i, StandardCharsets.US_ASCII);
            final int valueStart = keyEnd + 1;
            // headerEnd locates the terminating LF (folding-aware); include it in the value.
            final int valueEnd = Math.min(RawParseUtils.headerEnd(raw, valueStart) + 1, n);
            final byte[] value = new byte[valueEnd - valueStart];
            System.arraycopy(raw, valueStart, value, 0, value.length);
            result.add(Map.entry(key, value));
            i = valueEnd;
        }
        return result;
    }

    private static byte[] join(final List<Map.Entry<String, byte[]>> entries) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            for (final Map.Entry<String, byte[]> e : entries) {
                out.write(e.getKey().getBytes(StandardCharsets.US_ASCII));
                out.write(' ');
                final byte[] v = e.getValue();
                out.write(v);
                if (v.length == 0 || v[v.length - 1] != '\n') {
                    out.write('\n');
                }
            }
        } catch (final IOException ioe) {
            throw new UncheckedIOException(ioe);
        }
        return out.toByteArray();
    }
}
