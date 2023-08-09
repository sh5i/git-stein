package jp.ac.titech.c.se.stein.entry;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Abstract tree entry.
 */
public interface AnyColdEntry extends Serializable {
    Stream<Entry> stream();

    int size();

    default AnyColdEntry pack() {
        return size() == 1 ? ((Set) this).getEntries().get(0) : this;
    }

    static Entry of(int mode, String name, ObjectId id) {
        return new Entry(mode, name, id, null);
    }

    static Entry of(int mode, String name, ObjectId id, String directory) {
        return new Entry(mode, name, id, directory);
    }

    static Set of(Collection<Entry> entries) {
        return new Set(entries);
    }

    static Empty empty() {
        return new Empty();
    }

    /**
     * A set of multiple tree entries.
     */
    @NoArgsConstructor
    @EqualsAndHashCode
    class Set implements AnyColdEntry {
        private static final long serialVersionUID = 1L;

        @Getter
        private final List<Entry> entries = new ArrayList<>();

        public Set(Collection<Entry> entries) {
            this.entries.addAll(entries);
        }

        public void add(final Entry entry) {
            entries.add(entry);
        }

        @Override
        public Stream<Entry> stream() {
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
    class Empty implements AnyColdEntry {
        private static final long serialVersionUID = 1L;

        private Empty() {}

        @Override
        public Stream<Entry> stream() {
            return Stream.empty();
        }

        @Override
        public int size() {
            return 0;
        }
    }
}
