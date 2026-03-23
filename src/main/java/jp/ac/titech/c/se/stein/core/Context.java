package jp.ac.titech.c.se.stein.core;

import java.util.AbstractMap;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jp.ac.titech.c.se.stein.Application;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;

/**
 * An immutable context that carries the current state through the rewriting pipeline.
 *
 * <p>Contexts are created via {@link #init()} and extended via {@link #with}, which returns
 * a new instance without modifying the original. Internally backed by an array indexed by
 * {@link Key#ordinal()}, making lookups and copies lightweight.</p>
 *
 * <p>Implements {@link Map} as a read-only view; mutation methods throw
 * {@link UnsupportedOperationException}.</p>
 */
public class Context implements Map<Context.Key, Object> {
    /**
     * The keys that can be stored in a context.
     */
    public enum Key {
        commit, path, entry, rev, tag, ref, conf, inserter;

        public static final Key[] ALL = Key.values();
        public static final int SIZE = ALL.length;
    }

    /**
     * Cached result of {@link #toString()}.
     */
    private transient String cache;

    /**
     * Values indexed by {@link Key#ordinal()}.
     */
    private final Object[] values;

    private Context(final Object[] values) {
        this.values = values;
    }

    /**
     * Creates an empty context.
     */
    public static Context init() {
        return new Context(new Object[Key.SIZE]);
    }

    /**
     * Returns a new context with the given key-value pair added (or replaced).
     */
    public Context with(final Key k, final Object v) {
        final Object[] newValues = values.clone();
        newValues[k.ordinal()] = v;
        return new Context(newValues);
    }

    /**
     * Returns a new context with the given two key-value pairs added (or replaced).
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
        return v != null ? e.getKey() + ": " + v : null;
    }

    protected static String getStringValue(final Key key, final Object value) {
        return switch (key) {
            case commit -> ((RevCommit) value).name();
            case path -> '"' + (String) value + '"';
            case entry -> value.toString();
            case tag -> ((RevTag) value).name();
            case ref -> ((Ref) value).getName();
            default -> null;
        };
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

    // Typed accessors

    /**
     * Returns the current revision object, or {@code null} if not set.
     */
    public RevObject getRev() {
        return (RevObject) get(Key.rev);
    }

    /**
     * Returns the current commit, or {@code null} if not set.
     */
    public RevCommit getCommit() {
        return (RevCommit) get(Key.commit);
    }

    /**
     * Returns the current tree path, or {@code null} if not set.
     */
    public String getPath() {
        return (String) get(Key.path);
    }

    /**
     * Returns the current entry, or {@code null} if not set.
     */
    public jp.ac.titech.c.se.stein.entry.Entry getEntry() {
        return (jp.ac.titech.c.se.stein.entry.Entry) get(Key.entry);
    }

    /**
     * Returns the current ref, or {@code null} if not set.
     */
    public Ref getRef() {
        return (Ref) get(Key.ref);
    }

    /**
     * Returns the current config, or {@code null} if not set.
     */
    public Application.Config getConfig() {
        return (Application.Config) get(Key.conf);
    }

    /**
     * Returns the current object inserter, or {@code null} if not set.
     */
    public ObjectInserter getInserter() {
        return (ObjectInserter) get(Key.inserter);
    }
}
