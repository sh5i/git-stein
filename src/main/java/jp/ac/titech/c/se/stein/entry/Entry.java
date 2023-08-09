package jp.ac.titech.c.se.stein.entry;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.eclipse.jgit.lib.ObjectId;

import java.util.stream.Stream;

/**
 * A normal tree entry.
 */
@RequiredArgsConstructor
@EqualsAndHashCode
public class Entry implements AnyColdEntry, SingleEntry {
    private static final long serialVersionUID = 1L;

    public static Entry of(int mode, String name, ObjectId id) {
        return new Entry(mode, name, id, null);
    }

    public static Entry of(int mode, String name, ObjectId id, String directory) {
        return new Entry(mode, name, id, directory);
    }

    @Getter
    public final int mode;

    @Getter
    public final String name;

    @Getter
    public final ObjectId id;

    @Getter
    public final String directory;

    @EqualsAndHashCode.Exclude
    public transient Object data;

    @Override
    public String toString() {
        return String.format("<E:%s %s %o>", getPath(), id.name(), mode);
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
