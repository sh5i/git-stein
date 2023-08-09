package jp.ac.titech.c.se.stein.entry;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;

/**
 * The general interface for tree/blob entries.
 */
public interface HotEntry {
    /**
     * Entry = a sequence of single entries.
     */
    Stream<? extends Single> stream();

    int size();

    ColdEntry fold(RepositoryAccess target, Context c);

    static SourceBlob of(ColdEntry.Single e, RepositoryAccess source) {
        return new SourceBlob(e, source);
    }

    static NewBlob of(ColdEntry.Single e, byte[] updatedBlob) {
        return new NewBlob(e.getMode(), e.getName(), updatedBlob, e.getDirectory());
    }

    static NewBlob of(int mode, String name, byte[] blob) {
        return new NewBlob(mode, name, blob, null);
    }

    static NewBlob of(int mode, String name, byte[] blob, String directory) {
        return new NewBlob(mode, name, blob, directory);
    }

    static Set of(Collection<Single> entries) {
        return new Set(entries);
    }

    static Set set() {
        return new Set();
    }

    static Empty empty() {
        return new Empty();
    }

    abstract class Single implements HotEntry, SingleEntry {
        public abstract byte[] getBlob();

        public abstract long getBlobSize();

        @Override
        public Stream<? extends Single> stream() {
            return Stream.of(this);
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public ColdEntry.Single fold(RepositoryAccess target, Context c) {
            return ColdEntry.of(getMode(), getName(), target.writeBlob(getBlob(), c), getDirectory());
        }

        public NewBlob rename(final String newName) {
            return HotEntry.of(getMode(), newName, getBlob(), getDirectory());
        }

        public NewBlob update(final byte[] newBlob) {
            return HotEntry.of(getMode(), getName(), newBlob, getDirectory());
        }
    }

    /**
     * A normal tree entry.
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    class SourceBlob extends Single {
        @Delegate(types = SingleEntry.class)
        private final ColdEntry.Single entry;

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
            return String.format("<SourceFileEntry:%o %s %s>", getMode(), getPath(), getId().name());
        }
    }

    /**
     * A normal tree entry.
     */
    @Slf4j
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    class NewBlob extends Single {
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
            try (ObjectInserter inserter = new ObjectInserter.Formatter()) {
                return inserter.idFor(Constants.OBJ_BLOB, blob);
            }
        }

        @Override
        public String toString() {
            return String.format("<NewFileEntry:%o %s ...>", getMode(), getPath());
        }
    }

    /**
     * A set of multiple tree entries.
     */
    class Set implements HotEntry {
        @Getter
        private final List<Single> entries = new ArrayList<>();

        Set() {}

        Set(final Collection<Single> entries) {
            this.entries.addAll(entries);
        }

        public void add(final Single entry) {
            entries.add(entry);
        }

        @Override
        public Stream<Single> stream() {
            return entries.stream();
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public ColdEntry fold(RepositoryAccess target, Context c) {
            return ColdEntry.of(stream()
                    .map(e -> e.fold(target, c))
                    .collect(Collectors.toList()))
                    .pack();
        }

        @Override
        public String toString() {
            return entries.toString();
        }
    }

    class Empty implements HotEntry {
        Empty() {}

        @Override
        public Stream<Single> stream() {
            return Stream.empty();
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public ColdEntry.Empty fold(RepositoryAccess target, Context c) {
            return ColdEntry.empty();
        }
    }
}
