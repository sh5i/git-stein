package jp.ac.titech.c.se.stein.core;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public class Cache<K, V> extends AbstractMap<K, V> {
    private final Map<K, V> frontend, backend;

    public Cache(final Map<K, V> frontend, final Map<K, V> backend) {
        this.frontend = frontend;
        this.backend = backend;
    }

    public Cache(final Map<K, V> frontend, final Map<K, V> backend, final Predicate<K> condition) {
        this(frontend, new Filter<>(backend, condition));
    }

    @Override
    public V get(final Object key) {
        return frontend.computeIfAbsent((K) key, backend::get);
    }

    @Override
    public V put(final K key, final V value) {
        backend.put(key, value);
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

    public static class Filter<K, V> extends AbstractMap<K, V> {
        private final Map<K, V> delegatee;
        private final Predicate<K> condition;

        public Filter(final Map<K, V> delegatee, final Predicate<K> condition) {
            this.delegatee = delegatee;
            this.condition = condition;
        }

        @Override
        public V get(final Object key) {
            return condition.test((K) key) ? delegatee.get(key) : null;
        }

        @Override
        public V put(final K key, final V value) {
            return condition.test((K) key) ? delegatee.put(key, value) : value;
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            return delegatee.entrySet();
        }
    }

    public static class PutOnly<K, V> extends AbstractMap<K, V> {
        private final Map<K, V> delegatee;

        public PutOnly(final Map<K, V> delegatee) {
            this.delegatee = delegatee;
        }

        @Override
        public V get(final Object key) {
            return null;
        }

        @Override
        public V put(final K key, final V value) {
            return delegatee.put(key, value);
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            return Collections.emptySet();
        }
    }
}
