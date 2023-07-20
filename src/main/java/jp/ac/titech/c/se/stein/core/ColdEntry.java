package jp.ac.titech.c.se.stein.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Abstract tree entry.
 */
public interface ColdEntry extends Serializable {
    Stream<HashEntry> stream();

    int size();

    default boolean isEmpty() {
        return size() == 0;
    }

    Empty EMPTY = new Empty();

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

        public HashEntry(final int mode, final String name, final ObjectId id) {
            this(mode, name, id, null);
        }

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
    @EqualsAndHashCode
    class HashEntrySet implements ColdEntry {
        private static final long serialVersionUID = 1L;

        @Getter
        private final List<HashEntry> entries = new ArrayList<>();

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
     * An empty set of tree entries.
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
