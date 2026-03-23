package jp.ac.titech.c.se.stein.entry;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * A polymorphic Cold entry that represents zero, one, or multiple {@link Entry} instances.
 *
 * @see AnyHotEntry
 * @see Set
 * @see Empty
 */
public interface AnyColdEntry extends Serializable {
    /**
     * Returns the contained entries as a stream.
     */
    Stream<Entry> stream();

    /**
     * Returns the number of contained entries.
     */
    int size();

    /**
     * Normalizes this entry. A {@link Set} of size 0 becomes {@link Empty},
     * size 1 is unwrapped to its sole {@link Entry}, and others remain as-is.
     */
    default AnyColdEntry normalize() {
        return this;
    }

    /**
     * Creates a {@link Set} from the given collection.
     */
    static Set set(Collection<Entry> entries) {
        return new Set(entries);
    }

    /**
     * Creates a {@link Set} from the given entries.
     */
    static Set set(Entry... entries) {
        return new Set(Arrays.asList(entries));
    }

    /**
     * Creates an empty {@link Set}. Entries can be added later via {@link Set#add(Entry)}.
     */
    static Set set() {
        return new Set();
    }

    /**
     * Creates an {@link Empty} instance.
     */
    static Empty empty() {
        return new Empty();
    }

    /**
     * A collection of multiple {@link Entry} instances.
     * Use {@link #normalize()} to normalize after construction.
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
        public AnyColdEntry normalize() {
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
     * An entry containing no entries.
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

        @Override
        public String toString() {
            return "[]";
        }
    }
}
