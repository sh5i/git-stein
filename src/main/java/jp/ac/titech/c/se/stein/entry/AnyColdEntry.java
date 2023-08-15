package jp.ac.titech.c.se.stein.entry;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Abstract tree entry.
 */
public interface AnyColdEntry extends Serializable {
    Stream<Entry> stream();

    int size();

    default AnyColdEntry pack() {
        return this;
    }

    static Set set(Collection<Entry> entries) {
        return new Set(entries);
    }

    static Set set() {
        return new Set();
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

        @Override
        public AnyColdEntry pack() {
            switch (size()) {
                case 0:
                    return empty();
                case 1:
                    return entries.get(0);
                default:
                    return this;
            }
        }
    }

    /**
     * An empty set of hash entries.
     */
    @EqualsAndHashCode
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
