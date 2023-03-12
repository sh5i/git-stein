package jp.ac.titech.c.se.stein.core;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;

/**
 * An array for preserving the rewriting context.
 */
public class Context implements Map<Context.Key, Object> {
    /**
     * The keys of the values in a context.
     */
    public enum Key {
        commit, path, entry, rev, tag, ref, conf, inserter;

        public static final Key[] ALL = Key.values();
        public static final int SIZE = ALL.length;
    }

    /**
     * A cache of toString() result.
     */
    private transient String cache;

    /**
     * All values in this context.
     */
    private final Object[] values;

    /**
     * The constructor.
     */
    private Context(final Object[] values) {
        this.values = values;
    }

    /**
     * Returns an empty context.
     */
    public static Context init() {
        return new Context(new Object[Key.SIZE]);
    }

    /**
     * Returns an updated context by the given key-value pair.
     */
    public Context with(final Key k, final Object v) {
        final Object[] newValues = values.clone();
        newValues[k.ordinal()] = v;
        return new Context(newValues);
    }

    /**
     * Returns an updated context by the given key-value pairs.
     */
    public Context with(final Key k1, final Object v1, final Key k2, final Object v2) {
        final Object[] newValues = values.clone();
        newValues[k1.ordinal()] = v1;
        newValues[k2.ordinal()] = v2;
        return new Context(newValues);
    }

    @Override
    public String toString() {
        if (cache == null) {
            cache = doToString();
        }
        return cache;
    }

    protected String doToString() {
        final String result = Stream.of(Key.ALL)
                .map(this::toEntry)
                .filter(Objects::nonNull)
                .map(this::entryToString)
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
        return result.isEmpty() ? result : "(" + result + ")";
    }

    protected String entryToString(final Map.Entry<Key, Object> e) {
        final String v = getStringValue(e.getKey(), e.getValue());
        return v != null ? e.getKey() + ": \"" + v + '"' : null;
    }

    protected static String getStringValue(final Key key, final Object value) {
        switch (key) {
        case commit:
            return ((RevCommit) value).name();
        case path:
            return (String) value;
        case entry:
            return value.toString();
        case tag:
            return ((RevTag) value).name();
        case ref:
            return ((Ref) value).getName();
        default:
            return null;
        }
    }

    @Override
    public int size() {
        return keySet().size();
    }

    @Override
    public boolean isEmpty() {
        return size() == 0;
    }

    @Override
    public boolean containsKey(final Object key) {
        return key instanceof Key && values[((Key) key).ordinal()] != null;
    }

    @Override
    public boolean containsValue(final Object value) {
        return Arrays.asList(values).contains(value);
    }

    @Override
    public Object get(final Object key) {
        return key instanceof Key ? get((Key) key) : null;
    }

    public Object get(final Key key) {
        return values[key.ordinal()];
    }

    @Override
    public String put(final Key key, final Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String remove(final Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(final Map<? extends Key, ?> m) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Key> keySet() {
        return Stream.of(Key.ALL).filter(k -> values[k.ordinal()] != null).collect(Collectors.toSet());
    }

    @Override
    public Collection<Object> values() {
        return Stream.of(values).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    @Override
    public Set<Entry<Key, Object>> entrySet() {
        return Stream.of(Key.ALL).map(this::toEntry).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private Map.Entry<Key, Object> toEntry(final Key k) {
        final Object v = values[k.ordinal()];
        return v != null ? new AbstractMap.SimpleEntry<>(k, v) : null;
    }

    // Utility methods

    /**
     * Returns the revision object in the context.
     */
    public RevObject getRev() {
        return (RevObject) get(Key.rev);
    }

    /**
     * Returns the commit object in the context.
     */
    public RevCommit getCommit() {
        return (RevCommit) get(Key.commit);
    }

    /**
     * Returns the path in the context.
     */
    public String getPath() {
        return (String) get(Key.path);
    }

    /**
     * Returns the entry object in the context.
     */
    public EntrySet.Entry getEntry() {
        return (EntrySet.Entry) get(Key.entry);
    }

    /**
     * Returns the ref object in the context.
     */
    public Ref getRef() {
        return (Ref) get(Key.ref);
    }

    /**
     * Returns the inserter in the context.
     */
    public ObjectInserter getInserter() {
        return (ObjectInserter) get(Key.inserter);
    }
}
