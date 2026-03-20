package jp.ac.titech.c.se.stein.entry;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.util.HashUtils;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

/**
 * A Hot (data-bearing) single tree entry that holds or lazily loads the actual blob content.
 *
 * <p>This is the abstract base on the Hot side of the entry hierarchy, implementing both
 * {@link AnyHotEntry} (as a singleton collection) and {@link SingleEntry}.</p>
 *
 * @see Entry
 * @see AnyHotEntry
 * @see SourceBlob
 * @see NewBlob
 */
public abstract class HotEntry implements AnyHotEntry, SingleEntry {
    /**
     * Creates a {@link SourceBlob} that lazily reads blob content from the given source.
     */
    public static SourceBlob of(Entry e, RepositoryAccess source) {
        return new SourceBlob(e, source);
    }

    /**
     * Creates a {@link NewBlob} by replacing the blob content of an existing entry.
     */
    public static NewBlob of(Entry e, byte[] updatedBlob) {
        return new NewBlob(e.getMode(), e.getName(), updatedBlob, e.getDirectory());
    }

    /**
     * Creates a {@link NewBlob} with the given properties.
     */
    public static NewBlob of(int mode, String name, byte[] blob) {
        return new NewBlob(mode, name, blob, null);
    }

    /**
     * Creates a {@link NewBlob} with the given properties.
     */
    public static NewBlob of(int mode, String name, byte[] blob, String directory) {
        return new NewBlob(mode, name, blob, directory);
    }

    public abstract byte[] getBlob();

    public abstract long getBlobSize();

    @Override
    public Stream<? extends HotEntry> stream() {
        return Stream.of(this);
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public Entry fold(RepositoryAccess target, Context c) {
        return Entry.of(getMode(), getName(), target.writeBlob(getBlob(), c), getDirectory());
    }

    /**
     * Returns a new {@link NewBlob} with the given name, keeping the blob content unchanged.
     */
    public NewBlob rename(final String newName) {
        return of(getMode(), newName, getBlob(), getDirectory());
    }

    /**
     * Returns a new {@link NewBlob} with the given blob content, keeping the name unchanged.
     */
    public NewBlob update(final byte[] newBlob) {
        return of(getMode(), getName(), newBlob, getDirectory());
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
    public static class SourceBlob extends HotEntry {
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
    public static class NewBlob extends HotEntry {
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
