package jp.ac.titech.c.se.stein.core;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;

/**
 * An associative list for keeping the calling context.
 */
public class Context implements Map<Context.Key, Object> {

    public enum Key {
        commit, commit_id, entry, id, path, ref, repo, root, tag, tree;
    }

    private final Context parent;

    private final Key key;

    private final Object value;

    private transient String cache;

    public Context(final Context parent, final Key key, final Object value) {
        this.parent = parent;
        this.key = key;
        this.value = value;
    }

    public Context(final Key key, final Object value) {
        this(null, key, value);
    }

    public Context with(final Key key, final Object value) {
        return new Context(this, key, value);
    }

    @Override
    public String toString() {
        if (cache == null) {
            cache = doToString();
        }
        return cache;
    }

    public String doToString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("{");
        appendKeyValue(sb);
        sb.append("}");
        return sb.toString();
    }

    protected void appendKeyValue(final StringBuilder sb) {
        sb.append(key).append(": ").append('"').append(value).append('"');
        if (parent != null) {
            sb.append(", ");
            parent.appendKeyValue(sb);
        }
    }

    @Override
    public int size() {
        return parent != null ? 1 + parent.size() : 1;
    }

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public boolean containsKey(Object key) {
        return key == this.key || parent != null && parent.containsKey(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return value.equals(this.value) || parent != null && parent.containsValue(value);
    }

    @Override
    public Object get(Object key) {
        if (key == this.key) {
            return value;
        } else if (parent != null) {
            return parent.get(key);
        }
        return null;
    }

    @Override
    public String put(Key key, Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public String remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(Map<? extends Key, ? extends Object> m) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Key> keySet() {
        final Set<Key> result = parent != null ? parent.keySet() : new HashSet<>();
        result.add(key);
        return result;
    }

    @Override
    public Collection<Object> values() {
        final Collection<Object> result = parent != null ? parent.values() : new HashSet<>();
        result.add(value);
        return result;
    }

    @Override
    public Set<Entry<Key, Object>> entrySet() {
        final Set<Entry<Key, Object>> result = parent != null ? parent.entrySet() : new HashSet<>();
        result.add(new ContextEntry());
        return result;
    }

    class ContextEntry implements Map.Entry<Key, Object> {
        @Override
        public Key getKey() {
            return key;
        }

        @Override
        public Object getValue() {
            return value;
        }

        @Override
        public String setValue(final Object value) {
            throw new UnsupportedOperationException();
        }
    }

    // Utility methods

    public RevCommit getCommit() {
        return (RevCommit) get(Key.commit);
    }

    public RevTag getTag() {
        return (RevTag) get(Key.tag);
    }

    public ObjectId getId() {
        return (ObjectId) get(Key.id);
    }

    public Ref getRef() {
        return (Ref) get(Key.ref);
    }

    public EntrySet.Entry getEntry() {
        return (EntrySet.Entry) get(Key.entry);
    }
}
