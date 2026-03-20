package jp.ac.titech.c.se.stein.entry;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.lib.ObjectId;

import java.util.stream.Stream;

/**
 * A Cold (hash-based) single tree entry that references blob content by {@link ObjectId}.
 * Conceptually a "ColdEntry", but named simply {@code Entry} as the most representative
 * entry type in the hierarchy.
 *
 * <p>This is the primary concrete type on the Cold side of the entry hierarchy.
 * It implements both {@link AnyColdEntry} (as a singleton collection) and {@link SingleEntry}.
 * Instances are {@link java.io.Serializable} for use in caching.</p>
 *
 * @see HotEntry
 * @see AnyColdEntry
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
@EqualsAndHashCode
public class Entry implements AnyColdEntry, SingleEntry {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an entry without specifying its directory.
     */
    public static Entry of(int mode, String name, ObjectId id) {
        return new Entry(mode, name, id, null);
    }

    /**
     * Creates an entry.
     */
    public static Entry of(int mode, String name, ObjectId id, String directory) {
        return new Entry(mode, name, id, directory);
    }

    @Getter
    public final int mode;

    /**
     * Empty string for root tree entries.
     */
    @Getter
    public final String name;

    @Getter
    public final ObjectId id;

    /**
     * Only set in path-sensitive mode; {@code null} otherwise.
     */
    @Getter
    public final String directory;

    /**
     * Transient extension point for attaching arbitrary metadata during rewriting.
     * Excluded from {@code equals}/{@code hashCode}.
     */
    @EqualsAndHashCode.Exclude
    public transient Object data;

    @Override
    public String toString() {
        return String.format("%s [%s:%o]", getPath(), id.name(), mode);
    }

    @Override
    public Stream<Entry> stream() {
        return Stream.of(this);
    }

    @Override
    public int size() {
        return 1;
    }
}
