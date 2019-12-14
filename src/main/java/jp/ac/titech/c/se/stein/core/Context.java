package jp.ac.titech.c.se.stein.core;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;

/**
 * An associative list for keeping the calling context.
 */
public class Context implements Map<Context.Key, Object> {

    public enum Key {
        commit, path, entry, rev, tag, ref, repo, inserter;

        public static final Key[] ALL = Key.values();
        public static final int SIZE = ALL.length;
    }

    private transient String cache;

    private final Object[] values;

    private Context(final Object[] values) {
        this.values = values;
    }

    public static Context init() {
        return new Context(new Object[Key.SIZE]);
    }

    public Context with(final Key k, final Object v) {
        final Object[] newValues = values.clone();
        newValues[k.ordinal()] = v;
        return new Context(newValues);
    }

    public Context with(final Key k1, final Object v1, final Key k2, final Object v2) {
        final Object[] newValues = values.clone();
        newValues[k1.ordinal()] = v1;
        newValues[k2.ordinal()] = v2;
        return new Context(newValues);
    }

    public Context with(final Key k1, final Object v1, final Key k2, final Object v2, final Key k3, final Object v3) {
        final Object[] newValues = values.clone();
        newValues[k1.ordinal()] = v1;
        newValues[k2.ordinal()] = v2;
        newValues[k3.ordinal()] = v3;
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
        return Stream.of(Key.ALL)
                .map(k -> toEntry(k))
                .filter(Objects::nonNull)
                .map(e -> entryToString(e))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
    }

    protected String entryToString(final Map.Entry<Key, Object> e) {
        final String v = getStringValue(e.getKey(), e.getValue());
        return v != null ? e.getKey() + ": \"" + v + '"' : null;
    }

    protected static String getStringValue(final Key key, final Object value) {
        switch (key) {
        case repo:
            return ((Repository) value).getDirectory().toString();
        case tag:
            return ((RevTag) value).name();
        case commit:
            return ((RevCommit) value).name();
        case ref:
            return ((Ref) value).getName();
        case path:
            return (String) value;
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
        return false;
    }

    @Override
    public boolean containsKey(final Object key) {
        return key instanceof Key && values[((Key) key).ordinal()] != null;
    }

    @Override
    public boolean containsValue(final Object value) {
        return Stream.of(values).anyMatch(v -> v == null ? value == null : v.equals(value));
    }

    @Override
    public Object get(final Object key) {
        return key instanceof Key ? values[((Key) key).ordinal()] : null;
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
    public void putAll(final Map<? extends Key, ? extends Object> m) {
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
        return Stream.of(Key.ALL).map(k -> toEntry(k)).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private Map.Entry<Key, Object> toEntry(final Key k) {
        final Object v = values[k.ordinal()];
        return v != null ? new AbstractMap.SimpleEntry<Key, Object>(k, v) : null;
    }

    // Utility methods

    public String getCommitId() {
        return getCommit().name();
    }

    public RevObject getRev() {
        return (RevObject) get(Key.rev);
    }

    public RevCommit getCommit() {
        return (RevCommit) get(Key.commit);
    }

    public RevTag getTag() {
        return (RevTag) get(Key.tag);
    }

    public String getPath() {
        return (String) get(Key.path);
    }

    public EntrySet.Entry getEntry() {
        return (EntrySet.Entry) get(Key.entry);
    }

    public Ref getRef() {
        return (Ref) get(Key.ref);
    }

    public ObjectInserter getInserter() {
        return (ObjectInserter) get(Key.inserter);
    }
}
