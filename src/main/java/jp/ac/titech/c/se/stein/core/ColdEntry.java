package jp.ac.titech.c.se.stein.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Abstract tree entry.
 */
public interface ColdEntry extends Serializable {
    Stream<HashEntry> stream();

    int size();

    default ColdEntry pack() {
        return size() == 1 ? ((HashEntrySet) this).getEntries().get(0) : this;
    }

    static HashEntry of(int mode, String name, ObjectId id) {
        return new HashEntry(mode, name, id, null);
    }

    static HashEntry of(int mode, String name, ObjectId id, String directory) {
        return new HashEntry(mode, name, id, directory);
    }

    static HashEntrySet of(Collection<HashEntry> entries) {
        return new HashEntrySet(entries);
    }

    static Empty empty() {
        return new Empty();
    }

    /**
     * A normal tree entry.
     */
    @RequiredArgsConstructor
    @EqualsAndHashCode
    class HashEntry implements ColdEntry, SingleEntry {
        private static final long serialVersionUID = 1L;

        @Getter
        public final int mode;

        @Getter
        public final String name;

        @Getter
        public final ObjectId id;

        @Getter
        public final String directory;

        @EqualsAndHashCode.Exclude
        public transient Object data;

        @Override
        public String toString() {
            return String.format("<Entry:%o %s %s>", mode, getPath(), id.name());
        }

        @Override
        public Stream<HashEntry> stream() {
            return Stream.of(this);
        }

        @Override
        public int size() {
            return 1;
        }
    }

    /**
     * A set of multiple tree entries.
     */
    @NoArgsConstructor
    @EqualsAndHashCode
    class HashEntrySet implements ColdEntry {
        private static final long serialVersionUID = 1L;

        @Getter
        private final List<HashEntry> entries = new ArrayList<>();

        public HashEntrySet(Collection<HashEntry> entries) {
            this.entries.addAll(entries);
        }

        public void add(final HashEntry entry) {
            entries.add(entry);
        }

        @Override
        public Stream<HashEntry> stream() {
            return entries.stream();
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public String toString() {
            return entries.toString();
        }
    }

    /**
     * An empty set of hash entries.
     */
    class Empty implements ColdEntry {
        private static final long serialVersionUID = 1L;

        private Empty() {}

        @Override
        public Stream<HashEntry> stream() {
            return Stream.empty();
        }

        @Override
        public int size() {
            return 0;
        }
    }
}
