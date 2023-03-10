package jp.ac.titech.c.se.stein.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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
    class Entry implements EntrySet {
        private static final long serialVersionUID = 1L;

        public final int mode;

        public final String name;

        public final ObjectId id;

        public final String directory;

        public transient Object data;

        public Entry(final int mode, final String name, final ObjectId id, final String directory) {
            this.mode = mode;
            this.name = name;
            this.id = id;
            this.directory = directory;
        }

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

        public boolean isRoot() {
            return isTree() && name.equals("");
        }

        public String generateSortKey() {
            return name + (isTree() ? "/" : "");
        }

        @Override
        public void registerTo(final List<Entry> out) {
            out.add(this);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, mode, name, directory);
        }

        @Override
        public boolean equals(final Object obj) {
            if (!(obj instanceof Entry)) {
                return false;
            }
            final Entry that = (Entry) obj;
            return Objects.equals(this.id, that.id) &&
                   Objects.equals(this.mode, that.mode) &&
                   Objects.equals(this.name, that.name) &&
                   Objects.equals(this.directory, that.directory);
        }
    }

    /**
     * A set of multiple tree entries.
     */
    class EntryList implements EntrySet {
        private static final long serialVersionUID = 1L;

        private final List<Entry> entries = new ArrayList<>();

        public EntryList() {}

        public List<Entry> entries() {
            return entries;
        }

        public void add(final Entry entry) {
            entries.add(entry);
        }

        @Override
        public String toString() {
            return entries.toString();
        }

        @Override
        public void registerTo(final List<Entry> out) {
            out.addAll(entries);
        }

        @Override
        public int hashCode() {
            return entries.hashCode();
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof EntryList && Objects.equals(this.entries, ((EntryList) obj).entries);
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
