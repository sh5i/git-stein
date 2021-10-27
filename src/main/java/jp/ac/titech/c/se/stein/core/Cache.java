package jp.ac.titech.c.se.stein.core;

import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class Cache<K, V> extends AbstractMap<K, V> {

    private final Map<K, V> frontend, backend;

    private final Predicate<K> condition;

    public Cache(final Map<K, V> frontend, final Map<K, V> backend, final Predicate<K> condition) {
        this.frontend = frontend;
        this.backend = backend;
        this.condition = condition;
    }

    @Override
    public V get(final Object key) {
        V result = frontend.get(key);
        if (result == null && condition.test((K) key)) {
            result = backend.get(key);
            if (result != null) {
                @SuppressWarnings("unchecked")
                final K k = (K) key;
                frontend.put(k, result);
            }
        }
        return result;
    }

    @Override
    public V put(final K key, final V value) {
        if (condition.test(key)) {
            backend.put(key, value);
        }
        return frontend.put(key, value);
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        final Set<Entry<K, V>> result = new HashSet<>();
        result.addAll(frontend.entrySet());
        result.addAll(backend.entrySet());
        return result;
    }

    @Override
    public void clear() {
        frontend.clear();
        backend.clear();
    }
}
