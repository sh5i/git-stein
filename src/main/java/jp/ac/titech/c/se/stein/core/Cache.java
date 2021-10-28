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

    @Override
    public V get(final Object key) {
        @SuppressWarnings("unchecked")
        final K k = (K) key;
        return frontend.computeIfAbsent(k, backend::get);
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
        private final Predicate<K> condition;
        private final Map<K, V> delegatee;

        public Filter(final Predicate<K> condition, final Map<K, V> delegatee) {
            this.condition = condition;
            this.delegatee = delegatee;
        }

        public static <K, V> Map<K, V> apply(final Predicate<K> condition, final Map<K, V> delegatee) {
            return new Filter<>(condition, delegatee);
        }

        @Override
        public V get(final Object key) {
            @SuppressWarnings("unchecked")
            final K k = (K) key;
            return condition.test(k) ? delegatee.get(key) : null;
        }

        @Override
        public V put(final K key, final V value) {
            return condition.test(key) ? delegatee.put(key, value) : value;
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

        public static <K, V> Map<K, V> apply(final Map<K, V> delegatee) {
            return new PutOnly<>(delegatee);
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
