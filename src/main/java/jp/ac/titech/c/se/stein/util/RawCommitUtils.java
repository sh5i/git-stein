package jp.ac.titech.c.se.stein.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.util.RawParseUtils;

/**
 * Low-level reading and writing of commit objects at the raw-byte level, used to preserve
 * arbitrary extra headers (e.g., {@code change-id}, {@code mergetag}) that
 * {@link org.eclipse.jgit.lib.CommitBuilder} cannot represent.
 */
public class RawCommitUtils {
    /**
     * Extracts the raw bytes between the committer line and the blank line of the given commit's
     * header section. These bytes carry arbitrary extra headers such as {@code encoding},
     * {@code gpgsig}, {@code change-id}, and {@code mergetag}. The returned bytes end with a
     * trailing {@code LF} (if any extra headers existed) and can be passed back to
     * {@link #buildCommit} to preserve them byte-for-byte.
     *
     * JGit offers generic header-parsing primitives ({@code headerStart}, {@code headerValue},
     * {@code headerEnd}) and per-header accessors ({@link RevCommit#getEncoding},
     * {@link RevCommit#getRawGpgSignature}), but no single API to obtain the whole extra-header
     * region. This composes {@link RawParseUtils#committer} and {@link RawParseUtils#commitMessage}
     * to do so; it is the read-side counterpart of {@link #buildCommit}.
     */
    public static byte[] extractExtraHeaders(final RevCommit commit) {
        final byte[] raw = commit.getRawBuffer();
        final int committerStart = RawParseUtils.committer(raw, 0);
        if (committerStart < 0) {
            return new byte[0];
        }
        final int afterCommitterLine = RawParseUtils.nextLF(raw, committerStart);
        // commitMessage points just past the blank line; the blank-line LF is at
        // (messageStart - 1), so extra headers occupy [afterCommitterLine, messageStart - 1).
        final int extraEnd = RawParseUtils.commitMessage(raw, 0) - 1;
        final int extraLen = extraEnd - afterCommitterLine;
        if (extraLen <= 0) {
            return new byte[0];
        }
        final byte[] extra = new byte[extraLen];
        System.arraycopy(raw, afterCommitterLine, extra, 0, extraLen);
        return extra;
    }

    /**
     * Builds the raw bytes of a commit object. The {@code extraHeaders} bytes are spliced
     * verbatim between the committer line and the blank line separating headers from the message.
     * The {@code encoding} (defaulting to UTF-8) is used to encode the author/committer names
     * and the message; it should match the {@code encoding} header in {@code extraHeaders}.
     *
     * This duplicates the serialization done by {@link org.eclipse.jgit.lib.CommitBuilder#build()},
     * which cannot be used here because it only emits the fixed set of headers it knows
     * ({@code tree}, {@code parent}, {@code author}, {@code committer}, {@code encoding},
     * {@code gpgsig}) and drops arbitrary extra headers. Author/committer formatting is delegated
     * to {@link PersonIdent#toExternalString()} to stay consistent with CommitBuilder.
     */
    public static byte[] buildCommit(final ObjectId[] parentIds, final ObjectId treeId,
            final PersonIdent author, final PersonIdent committer,
            final byte[] extraHeaders, final String message, final Charset encoding) {
        final Charset enc = encoding != null ? encoding : StandardCharsets.UTF_8;
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeHeader(out, "tree", treeId);
            for (final ObjectId p : parentIds) {
                writeHeader(out, "parent", p);
            }
            writeIdentHeader(out, "author", author, enc);
            writeIdentHeader(out, "committer", committer, enc);
            if (extraHeaders != null && extraHeaders.length > 0) {
                out.write(extraHeaders);
                if (extraHeaders[extraHeaders.length - 1] != '\n') {
                    out.write('\n');
                }
            }
            out.write('\n');
            out.write(message.getBytes(enc));
            return out.toByteArray();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeHeader(final ByteArrayOutputStream out, final String key, final ObjectId id) throws IOException {
        out.write(key.getBytes(StandardCharsets.US_ASCII));
        out.write(' ');
        out.write(id.name().getBytes(StandardCharsets.US_ASCII));
        out.write('\n');
    }

    private static void writeIdentHeader(final ByteArrayOutputStream out, final String key, final PersonIdent ident, final Charset encoding) throws IOException {
        out.write(key.getBytes(StandardCharsets.US_ASCII));
        out.write(' ');
        out.write(ident.toExternalString().getBytes(encoding));
        out.write('\n');
    }
}
