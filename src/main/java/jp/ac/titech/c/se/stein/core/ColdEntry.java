package jp.ac.titech.c.se.stein.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Abstract tree entry.
 */
public interface ColdEntry extends Serializable {
    void registerTo(List<HashEntry> out);

    Empty EMPTY = new Empty();

    /**
     * A normal tree entry.
     */
    @RequiredArgsConstructor
    @EqualsAndHashCode
    class HashEntry implements ColdEntry, Comparable<HashEntry> {
        private static final long serialVersionUID = 1L;

        public final int mode;

        public final String name;

        public final ObjectId id;

        public final String directory;

        @EqualsAndHashCode.Exclude
        public transient Object data;

        public HashEntry(final int mode, final String name, final ObjectId id) {
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
        public void registerTo(final List<HashEntry> out) {
            out.add(this);
        }

        @Override
        public int compareTo(final HashEntry other) {
            return generateSortKey().compareTo(other.generateSortKey());
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

        public int size() {
            return entries.size();
        }

        @Override
        public String toString() {
            return entries.toString();
        }

        @Override
        public void registerTo(final List<HashEntry> out) {
            out.addAll(entries);
        }
    }

    /**
     * An empty set of tree entries.
     */
    class Empty implements ColdEntry {
        private static final long serialVersionUID = 1L;

        private Empty() {}

        @Override
        public void registerTo(final List<HashEntry> out) {}
    }
}
