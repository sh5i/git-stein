package jp.ac.titech.c.se.stein.entry;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Common interface for a single tree entry.
 *
 * @see Entry
 * @see HotEntry
 */
public interface SingleEntry extends Comparable<SingleEntry> {
    /**
     * The kind of object an entry refers to.
     */
    enum Type {
        blob, tree, link
    }

    /**
     * Returns the file mode bits (e.g., regular file, tree, link).
     */
    int getMode();

    /**
     * Returns the entry name (file or directory name without path).
     */
    String getName();

    /**
     * Returns the Git object ID.
     */
    ObjectId getId();

    /**
     * Returns the parent directory path. Only set in path-sensitive mode; {@code null} otherwise.
     */
    String getDirectory();

    /**
     * Returns the full path ({@code directory/name}), or just the name if directory is {@code null}.
     */
    default String getPath() {
        return getDirectory() != null ? getDirectory() + "/" + getName() : getName();
    }

    /**
     * Returns {@code true} if this entry represents a tree (directory).
     */
    default boolean isTree() {
        return FileMode.TREE.equals(getMode());
    }

    /**
     * Returns {@code true} if this entry represents a link.
     */
    default boolean isLink() {
        return FileMode.GITLINK.equals(getMode());
    }

    /**
     * Returns {@code true} if this entry represents a blob (i.e., neither tree nor link).
     */
    default boolean isBlob() {
        return !isTree() && !isLink();
    }

    /**
     * Returns the {@link Type} corresponding to this entry's mode.
     */
    default Type getType() {
        if (isTree()) {
            return Type.tree;
        } else if (isLink()) {
            return Type.link;
        } else {
            return Type.blob;
        }
    }

    /**
     * Returns {@code true} if this is a root tree entry (tree with an empty name).
     */
    default boolean isRoot() {
        return isTree() && getName().isEmpty();
    }

    /**
     * Returns a key for sorting entries in Git tree order.
     * Trees are suffixed with {@code /} according to the Git specification.
     */
    default String sortKey() {
        return isTree() ? getName() + "/" : getName();
    }

    /**
     * Compares entries by their {@link #sortKey()}.
     */
    @Override
    default int compareTo(final SingleEntry other) {
        return sortKey().compareTo(other.sortKey());
    }
}
