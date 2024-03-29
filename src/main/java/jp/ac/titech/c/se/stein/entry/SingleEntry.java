package jp.ac.titech.c.se.stein.entry;

import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;

/**
 * Abstract single tree entry.
 */
public interface SingleEntry extends Comparable<SingleEntry> {
    enum Type {
        BLOB, TREE, LINK
    }

    int getMode();

    String getName();

    ObjectId getId();

    String getDirectory();

    default String getPath() {
        return getDirectory() != null ? getDirectory() + "/" + getName() : getName();
    }

    default boolean isTree() {
        return FileMode.TREE.equals(getMode());
    }

    default boolean isLink() {
        return FileMode.GITLINK.equals(getMode());
    }

    default boolean isBlob() {
        return !isTree() && !isLink();
    }

    default Type getType() {
        if (isTree()) {
            return Type.TREE;
        } else if (isLink()) {
            return Type.LINK;
        } else {
            return Type.BLOB;
        }
    }

    default boolean isRoot() {
        return isTree() && getName().equals("");
    }

    default String sortKey() {
        return isTree() ? getName() + "/" : getName();
    }

    @Override
    default int compareTo(final SingleEntry other) {
        return sortKey().compareTo(other.sortKey());
    }
}
