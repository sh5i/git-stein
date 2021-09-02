package jp.ac.titech.c.se.stein.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Abstract tree entry.
 */
public interface EntrySet {
    void registerTo(List<Entry> out);

    public static final EmptySet EMPTY = new EmptySet();

    /**
     * A normal tree entry.
     */
    public static class Entry implements EntrySet {
        public final FileMode mode;

        public final String name;

        public final ObjectId id;

        public final String directory;

        public Object data;

        public Entry(final FileMode mode, final String name, final ObjectId id, final String directory) {
            this.mode = mode;
            this.name = name;
            this.id = id;
            this.directory = directory;
        }

        public Entry(final FileMode mode, final String name, final ObjectId id) {
            this(mode, name, id, null);
        }

        public String getPath() {
            return directory != null ? directory + "/" + name : name;
        }

        @Override
        public String toString() {
            return String.format("<Entry:%s %s %s>", mode, getPath(), id.name());
        }

        public boolean isTree() {
            return FileMode.TREE.equals(mode.getBits());
        }

        public boolean isRoot() {
            return isTree() && name.equals("");
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
            final Entry other = (Entry) obj;
            return Objects.equals(id, other.id) && Objects.equals(mode, other.mode) && Objects.equals(name, other.name) && Objects.equals(directory, other.directory);
        }
    }

    /**
     * A set of multiple tree entries.
     */
    public static class EntryList implements EntrySet {

        private final List<Entry> entries = new ArrayList<>();

        public EntryList() {
        }

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
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            final EntryList other = (EntryList) obj;
            if (entries == null) {
                if (other.entries != null) {
                    return false;
                }
            } else if (!entries.equals(other.entries)) {
                return false;
            }
            return true;
        }
    }

    /**
     * An empty set of tree entries.
     */
    public static class EmptySet implements EntrySet {
        private EmptySet() {
        }

        @Override
        public void registerTo(final List<Entry> out) {
        }
    }
}
