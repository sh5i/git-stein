package jp.ac.titech.c.se.stein;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Abstract tree entry.
 */
public interface Entry {
    void registerTo(List<SingleEntry> out);

    public static SingleEntry of(final FileMode mode, final String name, final ObjectId id, final String pathContext) {
        return new SingleEntry(mode, name, id, pathContext);
    }

    public static EntrySet newSet() {
        return new EntrySet();
    }

    @SuppressWarnings("unchecked")
    public static final List<SingleEntry> EMPTY_ENTRIES = Collections.EMPTY_LIST;

    public static final EmptyEntry EMPTY = new EmptyEntry();

    /**
     * A normal tree entry.
     */
    public static class SingleEntry implements Entry {
        public final FileMode mode;

        public final String name;

        public final ObjectId id;

        public final String pathContext;

        private SingleEntry(final FileMode mode, final String name, final ObjectId id, final String path) {
            this.mode = mode;
            this.name = name;
            this.id = id;
            this.pathContext = path;
        }

        @Override
        public String toString() {
            if (pathContext != null) {
                return String.format("%s %s %s", mode, name, id);
            } else {
                return String.format("%s %s/%s %s", mode, pathContext, name, id);
            }
        }

        public boolean isTree() {
            return FileMode.TREE.equals(mode.getBits());
        }

        @Override
        public void registerTo(final List<SingleEntry> out) {
            out.add(this);
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (id == null ? 0 : id.hashCode());
            result = prime * result + (mode == null ? 0 : mode.hashCode());
            result = prime * result + (name == null ? 0 : name.hashCode());
            result = prime * result + (pathContext == null ? 0 : pathContext.hashCode());
            return result;
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
            final SingleEntry other = (SingleEntry) obj;
            if (id == null) {
                if (other.id != null) {
                    return false;
                }
            } else if (!id.equals(other.id)) {
                return false;
            }
            if (mode == null) {
                if (other.mode != null) {
                    return false;
                }
            } else if (!mode.equals(other.mode)) {
                return false;
            }
            if (name == null) {
                if (other.name != null) {
                    return false;
                }
            } else if (!name.equals(other.name)) {
                return false;
            }
            if (pathContext == null) {
                if (other.pathContext != null) {
                    return false;
                }
            } else if (!pathContext.equals(other.pathContext)) {
                return false;
            }
            return true;
        }
    }

    /**
     * A set of multiple tree entries.
     */
    public static class EntrySet implements Entry {

        private final List<SingleEntry> entries = new ArrayList<>();

        private EntrySet() {
        }

        public List<SingleEntry> entries() {
            return entries;
        }

        public void add(final SingleEntry entry) {
            entries.add(entry);
        }

        @Override
        public String toString() {
            return entries.toString();
        }

        @Override
        public void registerTo(final List<SingleEntry> out) {
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
            final EntrySet other = (EntrySet) obj;
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
    public static class EmptyEntry implements Entry {
        private EmptyEntry() {
        }

        @Override
        public void registerTo(final List<SingleEntry> out) {
        }
    }
}
