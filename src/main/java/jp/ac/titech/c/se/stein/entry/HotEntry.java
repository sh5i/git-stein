package jp.ac.titech.c.se.stein.entry;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;

import java.util.stream.Stream;

/**
 * A Hot (data-bearing) single tree entry.
 *
 * <p>This is the abstract base on the Hot side of the entry hierarchy, implementing both
 * {@link AnyHotEntry} (as a singleton collection) and {@link SingleEntry}.</p>
 *
 * @see BlobEntry
 * @see Entry
 */
public abstract class HotEntry implements AnyHotEntry, SingleEntry {
    /**
     * Creates a {@link BlobEntry.SourceBlob} that lazily reads blob content from the given source.
     */
    public static BlobEntry.SourceBlob of(Entry e, RepositoryAccess source) {
        return new BlobEntry.SourceBlob(e, source);
    }

    /**
     * Creates a {@link BlobEntry.NewBlob} by replacing the blob content of an existing entry.
     */
    public static BlobEntry.NewBlob of(Entry e, byte[] updatedBlob) {
        return new BlobEntry.NewBlob(e.getMode(), e.getName(), updatedBlob, e.getDirectory());
    }

    /**
     * Creates a {@link BlobEntry.NewBlob} with the given properties.
     */
    public static BlobEntry.NewBlob of(int mode, String name, byte[] blob) {
        return new BlobEntry.NewBlob(mode, name, blob, null);
    }

    /**
     * Creates a {@link BlobEntry.NewBlob} with the given properties.
     */
    public static BlobEntry.NewBlob of(int mode, String name, byte[] blob, String directory) {
        return new BlobEntry.NewBlob(mode, name, blob, directory);
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
