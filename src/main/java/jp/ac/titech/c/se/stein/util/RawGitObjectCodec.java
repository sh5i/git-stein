package jp.ac.titech.c.se.stein.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.util.RawParseUtils;

/**
 * Low-level reading and writing of commit and tag objects at the raw-byte level. Used to
 * preserve fields byte-for-byte that {@link org.eclipse.jgit.lib.CommitBuilder} /
 * {@link org.eclipse.jgit.lib.TagBuilder} would alter when re-serializing a parsed value —
 * arbitrary commit extra headers (e.g., {@code change-id}, {@code mergetag}) and legacy
 * (non-UTF-8) author/committer/tagger encodings.
 */
public class RawGitObjectCodec {
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
     * Returns the raw bytes of the author line's value (everything after {@code "author "} up to,
     * but excluding, the terminating {@code LF}), or {@code null} if absent. Preserving these
     * bytes keeps the original encoding of the name even when no {@code encoding} header is
     * present (e.g., legacy Latin-1 names), which re-serializing a parsed {@link PersonIdent}
     * would not.
     */
    public static byte[] rawAuthor(final RevCommit commit) {
        final byte[] raw = commit.getRawBuffer();
        return rawIdent(raw, RawParseUtils.author(raw, 0));
    }

    /**
     * Returns the raw bytes of the committer line's value. See {@link #rawAuthor}.
     */
    public static byte[] rawCommitter(final RevCommit commit) {
        final byte[] raw = commit.getRawBuffer();
        return rawIdent(raw, RawParseUtils.committer(raw, 0));
    }

    private static byte[] rawIdent(final byte[] raw, final int start) {
        if (start < 0) {
            return null;
        }
        final int afterLine = RawParseUtils.nextLF(raw, start);
        return Arrays.copyOfRange(raw, start, afterLine - 1);
    }

    /**
     * Returns the raw bytes of the commit message body (everything after the blank line that
     * separates the header section from the message).
     */
    public static byte[] rawMessage(final RevCommit commit) {
        final byte[] raw = commit.getRawBuffer();
        return Arrays.copyOfRange(raw, RawParseUtils.commitMessage(raw, 0), raw.length);
    }

    /**
     * Returns the raw bytes of the tagger line's value, or {@code null} if absent. See
     * {@link #rawAuthor}; tags have no {@code encoding} header, so preserving these bytes is the
     * only way to keep a legacy-encoded tagger name byte-for-byte.
     */
    public static byte[] rawTagger(final RevTag tag) {
        final byte[] raw = tag.getRawBuffer();
        return rawIdent(raw, RawParseUtils.tagger(raw, 0));
    }

    /**
     * Returns the raw bytes of the tag message body (everything after the blank line). For signed
     * tags this includes the trailing PGP signature block.
     */
    public static byte[] rawTagMessage(final RevTag tag) {
        final byte[] raw = tag.getRawBuffer();
        return Arrays.copyOfRange(raw, RawParseUtils.tagMessage(raw, 0), raw.length);
    }

    /**
     * Returns the raw bytes between the tagger line and the blank line of the given tag's header
     * section — the tag analog of {@link #extractExtraHeaders(RevCommit)}. Preserving this region
     * keeps a tag byte-for-byte even if it carries a header after {@code tagger} (e.g.
     * {@code encoding}).
     *
     * <p>Rationale: such a header is <em>git-representable</em> (JGit's {@code TagBuilder} can write
     * one) and <em>git-readable</em> ({@code git cat-file} / {@code fsck} / {@code clone} all accept
     * it; {@code transfer.fsckObjects} defaults to false) — even though {@code git mktag} (strict
     * fsck, {@code extraHeaderEntry}) refuses to create one. Handling it gives <em>uniformity with
     * commits</em>, which keep their committer-to-blank region, so the byte-for-byte invariant holds
     * unconditionally. We have observed no such tag in practice; for every conformant tag this
     * region is empty and the method is a no-op.</p>
     */
    public static byte[] extractTagHeaders(final RevTag tag) {
        final byte[] raw = tag.getRawBuffer();
        final int taggerStart = RawParseUtils.tagger(raw, 0);
        if (taggerStart < 0) {
            return new byte[0];
        }
        final int afterTaggerLine = RawParseUtils.nextLF(raw, taggerStart);
        final int extraEnd = RawParseUtils.tagMessage(raw, 0) - 1;
        final int len = extraEnd - afterTaggerLine;
        if (len <= 0) {
            return new byte[0];
        }
        final byte[] extra = new byte[len];
        System.arraycopy(raw, afterTaggerLine, extra, 0, len);
        return extra;
    }

    /**
     * Builds the raw bytes of a commit object from already-serialized parts. The {@code author}
     * and {@code committer} bytes are the line values that follow {@code "author "} /
     * {@code "committer "}; {@code extraHeaders} are spliced verbatim before the blank line; and
     * {@code message} is the raw body bytes.
     *
     * This duplicates the serialization done by {@link org.eclipse.jgit.lib.CommitBuilder#build()},
     * which cannot be used here because it only emits the fixed set of headers it knows
     * ({@code tree}, {@code parent}, {@code author}, {@code committer}, {@code encoding},
     * {@code gpgsig}) and drops arbitrary extra headers.
     */
    public static byte[] buildCommit(final ObjectId[] parentIds, final ObjectId treeId,
            final byte[] author, final byte[] committer, final byte[] extraHeaders, final byte[] message) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeHeader(out, "tree", treeId);
            for (final ObjectId p : parentIds) {
                writeHeader(out, "parent", p);
            }
            out.write("author ".getBytes(StandardCharsets.US_ASCII));
            out.write(author);
            out.write('\n');
            out.write("committer ".getBytes(StandardCharsets.US_ASCII));
            out.write(committer);
            out.write('\n');
            if (extraHeaders != null && extraHeaders.length > 0) {
                out.write(extraHeaders);
                if (extraHeaders[extraHeaders.length - 1] != '\n') {
                    out.write('\n');
                }
            }
            out.write('\n');
            out.write(message);
            return out.toByteArray();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Convenience variant of {@link #buildCommit(ObjectId[], ObjectId, byte[], byte[], byte[], byte[])}
     * that serializes {@code author}/{@code committer} via {@link PersonIdent#toExternalString()}
     * (consistent with CommitBuilder) and encodes the message with {@code encoding} (default UTF-8).
     */
    public static byte[] buildCommit(final ObjectId[] parentIds, final ObjectId treeId,
            final PersonIdent author, final PersonIdent committer,
            final byte[] extraHeaders, final String message, final Charset encoding) {
        final Charset enc = encoding != null ? encoding : StandardCharsets.UTF_8;
        return buildCommit(parentIds, treeId,
                author.toExternalString().getBytes(enc), committer.toExternalString().getBytes(enc),
                extraHeaders, message.getBytes(enc));
    }

    /**
     * Builds the raw bytes of a tag object from already-serialized parts. The {@code tagger} bytes
     * are the line value that follows {@code "tagger "} ({@code null} omits the line, for tags
     * without a tagger); {@code extraHeaders} (see {@link #extractTagHeaders}) are spliced verbatim
     * after the tagger line and before the blank line; {@code message} is the raw body bytes
     * (including any trailing PGP signature for signed tags).
     *
     * This duplicates {@link org.eclipse.jgit.lib.TagBuilder#build()}, which re-serializes a parsed
     * {@link PersonIdent} tagger and re-encodes the message — altering bytes for legacy encodings.
     */
    public static byte[] buildTag(final ObjectId objectId, final int type, final String tag,
            final byte[] tagger, final byte[] extraHeaders, final byte[] message) {
        try {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            writeHeader(out, "object", objectId);
            out.write("type ".getBytes(StandardCharsets.US_ASCII));
            out.write(Constants.encodedTypeString(type));
            out.write('\n');
            out.write("tag ".getBytes(StandardCharsets.US_ASCII));
            out.write(tag.getBytes(StandardCharsets.UTF_8));
            out.write('\n');
            if (tagger != null) {
                out.write("tagger ".getBytes(StandardCharsets.US_ASCII));
                out.write(tagger);
                out.write('\n');
            }
            if (extraHeaders != null && extraHeaders.length > 0) {
                out.write(extraHeaders);
                if (extraHeaders[extraHeaders.length - 1] != '\n') {
                    out.write('\n');
                }
            }
            out.write('\n');
            out.write(message);
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
}
