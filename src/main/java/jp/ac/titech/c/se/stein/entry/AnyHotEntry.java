package jp.ac.titech.c.se.stein.entry;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The general interface for tree/blob entries.
 */
public interface AnyHotEntry {
    /**
     * Entry = a sequence of single entries.
     */
    Stream<? extends HotEntry> stream();

    int size();

    AnyColdEntry fold(RepositoryAccess target, Context c);

    static Set set(Collection<HotEntry> entries) {
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
    class Set implements AnyHotEntry {
        @Getter
        private final List<HotEntry> entries = new ArrayList<>();

        Set() {}

        Set(final Collection<HotEntry> entries) {
            this.entries.addAll(entries);
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
                    .collect(Collectors.toList()))
                    .pack();
        }

        @Override
        public String toString() {
            return entries.toString();
        }
    }

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
    }
}
