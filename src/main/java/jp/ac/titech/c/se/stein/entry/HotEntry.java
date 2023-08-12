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

public abstract class HotEntry implements AnyHotEntry, SingleEntry {
    public static SourceBlob of(Entry e, RepositoryAccess source) {
        return new SourceBlob(e, source);
    }

    public static NewBlob of(Entry e, byte[] updatedBlob) {
        return new NewBlob(e.getMode(), e.getName(), updatedBlob, e.getDirectory());
    }

    public static NewBlob of(int mode, String name, byte[] blob) {
        return new NewBlob(mode, name, blob, null);
    }

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

    public NewBlob rename(final String newName) {
        return of(getMode(), newName, getBlob(), getDirectory());
    }

    public NewBlob update(final byte[] newBlob) {
        return of(getMode(), getName(), newBlob, getDirectory());
    }

    public NewBlob update(final String newContent) {
        return update(newContent.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A normal tree entry.
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
     * A normal tree entry.
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

        @Override
        public ObjectId getId() {
            log.warn("Getting Object ID for NewFileEntry requires hash computation");
            return HashUtils.idFor(blob);
        }

        @Override
        public String toString() {
            return String.format("%s [new(%d):%o]", getPath(), getBlobSize(), getMode());
        }
    }
}
