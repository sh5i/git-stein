package jp.ac.titech.c.se.stein.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Abstract tree entry.
 */
public interface EntrySet extends Serializable {
    void registerTo(List<Entry> out);

    EmptySet EMPTY = new EmptySet();

    /**
     * A normal tree entry.
     */
    @RequiredArgsConstructor
    @EqualsAndHashCode
    class Entry implements EntrySet, Comparable<Entry> {
        private static final long serialVersionUID = 1L;

        public final int mode;

        public final String name;

        public final ObjectId id;

        public final String directory;

        @EqualsAndHashCode.Exclude
        public transient Object data;

        public Entry(final int mode, final String name, final ObjectId id) {
            this(mode, name, id, null);
        }

        public String getPath() {
            return directory != null ? directory + "/" + name : name;
        }

        @Override
        public String toString() {
            return String.format("<Entry:%o %s %s>", mode, getPath(), id.name());
        }

        public boolean isTree() {
            return FileMode.TREE.equals(mode);
        }

        public boolean isLink() {
            return FileMode.GITLINK.equals(mode);
        }

        public boolean isFile() {
            return !isTree() && !isLink();
        }

        public boolean isRoot() {
            return isTree() && name.equals("");
        }

        public String generateSortKey() {
            return isTree() ? name + "/" : name;
        }

        @Override
        public void registerTo(final List<Entry> out) {
            out.add(this);
        }

        @Override
        public int compareTo(final Entry other) {
            return generateSortKey().compareTo(other.generateSortKey());
        }
    }

    /**
     * A set of multiple tree entries.
     */
    @EqualsAndHashCode
    class EntryList implements EntrySet {
        private static final long serialVersionUID = 1L;

        @Getter
        private final List<Entry> entries = new ArrayList<>();

        public void add(final Entry entry) {
            entries.add(entry);
        }

        public int size() {
            return entries.size();
        }

        @Override
        public String toString() {
            return entries.toString();
        }

        @Override
        public void registerTo(final List<Entry> out) {
            out.addAll(entries);
        }
    }

    /**
     * An empty set of tree entries.
     */
    class EmptySet implements EntrySet {
        private static final long serialVersionUID = 1L;

        private EmptySet() {}

        @Override
        public void registerTo(final List<Entry> out) {}
    }
}
