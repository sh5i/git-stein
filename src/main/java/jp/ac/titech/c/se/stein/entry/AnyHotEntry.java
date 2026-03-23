package jp.ac.titech.c.se.stein.entry;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import lombok.Getter;
import org.eclipse.jgit.lib.ObjectId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A polymorphic Hot entry that represents zero, one, or multiple {@link HotEntry} instances.
 *
 * @see AnyColdEntry
 * @see Set
 * @see Empty
 */
public interface AnyHotEntry {
    /**
     * Returns the contained entries as a stream.
     */
    Stream<? extends HotEntry> stream();

    /**
     * Returns the number of contained entries.
     */
    int size();

    /**
     * Converts this Hot entry to a Cold entry by writing blob data to the target repository.
     */
    AnyColdEntry fold(RepositoryAccess target, Context c);

    /**
     * Creates a {@link Set} from the given collection.
     */
    static Set set(final Collection<HotEntry> entries) {
        return new Set(entries);
    }

    /**
     * Creates a {@link Set} from the given entries.
     */
    static Set set(final HotEntry... entries) {
        return new Set(entries);
    }

    /**
     * Creates an empty {@link Set}. Entries can be added later via {@link Set#add(HotEntry)}.
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
     * A collection of multiple {@link HotEntry} instances.
     * Used when a blob transformation produces multiple output entries.
     */
    class Set implements AnyHotEntry {
        @Getter
        private final List<HotEntry> entries = new ArrayList<>();

        Set() {}

        Set(final Collection<HotEntry> entries) {
            this.entries.addAll(entries);
        }

        Set(final HotEntry... entries) {
            this(Arrays.asList(entries));
        }

        public void add(final HotEntry entry) {
            entries.add(entry);
        }

        @Override
        public Stream<HotEntry> stream() {
            return entries.stream();
        }

        @Override
        public int size() {
            return entries.size();
        }

        @Override
        public AnyColdEntry fold(RepositoryAccess target, Context c) {
            return AnyColdEntry.set(stream()
                    .map(e -> e.fold(target, c))
                    .filter(e -> !e.getId().equals(ObjectId.zeroId()))
                    .collect(Collectors.toList()))
                    .pack();
        }

        @Override
        public String toString() {
            return entries.toString();
        }
    }

    /**
     * An entry containing no entries.
     */
    class Empty implements AnyHotEntry {
        Empty() {}

        @Override
        public Stream<HotEntry> stream() {
            return Stream.empty();
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public AnyColdEntry.Empty fold(RepositoryAccess target, Context c) {
            return AnyColdEntry.empty();
        }

        @Override
        public String toString() {
            return "[]";
        }
    }
}
