package jp.ac.titech.c.se.stein.entry;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.util.HashUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;

import java.nio.charset.StandardCharsets;

/**
 * A Hot entry representing a blob (file content).
 *
 * @see SourceBlob
 * @see NewBlob
 */
public abstract class BlobEntry extends HotEntry {
    public abstract byte[] getBlob();

    /**
     * Returns the blob content as a UTF-8 string.
     */
    public String getContent() {
        return new String(getBlob(), StandardCharsets.UTF_8);
    }

    public abstract long getBlobSize();

    @Override
    public Entry fold(RepositoryAccess target, Context c) {
        return Entry.of(getMode(), getName(), target.writeBlob(getBlob(), c), getDirectory());
    }

    /**
     * Returns a new {@link NewBlob} with the given name, keeping the blob content unchanged.
     */
    public NewBlob rename(final String newName) {
        return new NewBlob(getMode(), newName, getBlob(), getDirectory());
    }

    /**
     * Returns a new {@link NewBlob} with the given blob content, keeping the name unchanged.
     */
    public NewBlob update(final byte[] newBlob) {
        return new NewBlob(getMode(), getName(), newBlob, getDirectory());
    }

    /**
     * String variant of {@link #update(byte[])}.
     */
    public NewBlob update(final String newContent) {
        return update(newContent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A Hot entry backed by an existing blob in a repository.
     * The blob content is lazily loaded on the first call to {@link #getBlob()}.
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    public static class SourceBlob extends BlobEntry {
        @Delegate(types = SingleEntry.class)
        private final Entry entry;

        private final RepositoryAccess source;

        private byte[] blob;

        @Override
        public byte[] getBlob() {
            if (blob == null) {
                blob = source.readBlob(entry.id);
            }
            return blob;
        }

        @Override
        public long getBlobSize() {
            return blob != null ? blob.length : source.getBlobSize(entry.id);
        }

        @Override
        public String toString() {
            return String.format("%s [hot(%s):%o]", getPath(), getId().name(), getMode());
        }
    }

    /**
     * A Hot entry holding new or transformed blob data directly.
     */
    @Slf4j
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    public static class NewBlob extends BlobEntry {
        @Getter
        private final int mode;

        @Getter
        private final String name;

        @Getter
        private final byte[] blob;

        @Getter
        private final String directory;

        @Override
        public long getBlobSize() {
            return blob.length;
        }

        /**
         * Computes and returns the SHA-1 hash of the blob data.
         * Since a {@link NewBlob} has no pre-existing object ID, this requires
         * hash computation on every call and logs a warning, as it is typically
         * not intended in normal usage.
         */
        @Override
        public ObjectId getId() {
            log.warn("Getting Object ID for NewBlob requires hash computation");
            return HashUtils.idFor(blob);
        }

        @Override
        public String toString() {
            return String.format("%s [new(%d):%o]", getPath(), getBlobSize(), getMode());
        }
    }
}
