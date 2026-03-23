package jp.ac.titech.c.se.stein.entry;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;

import java.util.List;
import java.util.stream.Stream;

/**
 * A Hot (data-bearing) single tree entry.
 *
 * <p>This is the abstract base on the Hot side of the entry hierarchy, implementing both
 * {@link AnyHotEntry} (as a singleton collection) and {@link SingleEntry}.</p>
 *
 * @see BlobEntry
 * @see TreeEntry
 * @see Entry
 */
public abstract class HotEntry implements AnyHotEntry, SingleEntry {
    /**
     * Creates a {@link BlobEntry} that lazily reads blob content from the given source.
     */
    public static BlobEntry of(Entry e, RepositoryAccess source) {
        return new BlobEntry.SourceBlob(e, source);
    }

    /**
     * Creates a {@link BlobEntry} by replacing the blob content of an existing entry.
     */
    public static BlobEntry of(Entry e, byte[] updatedBlob) {
        return new BlobEntry.NewBlob(e.getMode(), e.getName(), updatedBlob, e.getDirectory());
    }

    /**
     * Creates a {@link BlobEntry} with the given properties.
     */
    public static BlobEntry of(int mode, String name, byte[] blob) {
        return new BlobEntry.NewBlob(mode, name, blob, null);
    }

    /**
     * Creates a {@link BlobEntry} with the given properties.
     */
    public static BlobEntry of(int mode, String name, byte[] blob, String directory) {
        return new BlobEntry.NewBlob(mode, name, blob, directory);
    }

    /**
     * Creates a {@link TreeEntry} that lazily reads tree contents from the given source.
     *
     * @param directory the directory path to set on child entries, or {@code null}
     */
    public static TreeEntry ofTree(Entry e, RepositoryAccess source, String directory) {
        return new TreeEntry.SourceTree(e, source, directory);
    }

    /**
     * Creates a {@link TreeEntry.NewTree} with the given name and children.
     */
    public static TreeEntry.NewTree ofTree(String name, List<HotEntry> children) {
        return new TreeEntry.NewTree(name, children);
    }

    /**
     * Creates a {@link TreeEntry.NewTree} with the given name and children.
     */
    public static TreeEntry.NewTree ofTree(String name, HotEntry... children) {
        return new TreeEntry.NewTree(name, children);
    }

    @Override
    public Stream<? extends HotEntry> stream() {
        return Stream.of(this);
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public abstract Entry fold(RepositoryAccess target, Context c);
}
