package jp.ac.titech.c.se.stein.core;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Delegate;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jp.ac.titech.c.se.stein.core.ColdEntry.HashEntry;
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
    Stream<? extends SingleHotEntry> stream();

    int size();

    ColdEntry fold(RepositoryAccess target, Context c);

    static SourceFileEntry of(HashEntry e, RepositoryAccess source) {
        return new SourceFileEntry(e, source);
    }

    static NewFileEntry of(HashEntry e, byte[] updatedBlob) {
        return new NewFileEntry(e.getMode(), e.getName(), updatedBlob, e.getDirectory());
    }

    static NewFileEntry of(int mode, String name, byte[] blob) {
        return new NewFileEntry(mode, name, blob, null);
    }

    static NewFileEntry of(int mode, String name, byte[] blob, String directory) {
        return new NewFileEntry(mode, name, blob, directory);
    }

    static Empty empty() {
        return new Empty();
    }

    abstract class SingleHotEntry implements HotEntry, SingleEntry {
        public abstract byte[] getBlob();

        @Override
        public Stream<? extends SingleHotEntry> stream() {
            return Stream.of(this);
        }

        @Override
        public int size() {
            return 1;
        }

        @Override
        public HashEntry fold(RepositoryAccess target, Context c) {
            return ColdEntry.of(getMode(), getName(), target.writeBlob(getBlob(), c), getDirectory());
        }
    }

    /**
     * A normal tree entry.
     */
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    class SourceFileEntry extends SingleHotEntry {
        @Delegate(types = SingleEntry.class)
        private final HashEntry entry;

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
        public String toString() {
            return String.format("<SourceFileEntry:%o %s %s>", getMode(), getPath(), getId().name());
        }
    }

    /**
     * A normal tree entry.
     */
    @Slf4j
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE)
    class NewFileEntry extends SingleHotEntry {
        @Getter
        private final int mode;

        @Getter
        private final String name;

        @Getter
        private final byte[] blob;

        @Getter
        private final String directory;

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
    class EntrySet implements HotEntry {
        @Getter
        private final List<SingleHotEntry> entries = new ArrayList<>();

        public void add(final SingleHotEntry entry) {
            entries.add(entry);
        }

        @Override
        public Stream<SingleHotEntry> stream() {
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
        private Empty() {}

        @Override
        public Stream<SingleHotEntry> stream() {
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
