package jp.ac.titech.c.se.stein.core;

import jp.ac.titech.c.se.stein.entry.AnyColdEntry;
import jp.ac.titech.c.se.stein.entry.Entry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Cache provider backed by H2 MVStore.
 * Data is stored in a single file ({@code cache.mv.db}) in the target repository's .git directory.
 */
public class MVStoreCacheProvider implements CacheProvider {
    private static final Logger log = LoggerFactory.getLogger(MVStoreCacheProvider.class);

    private final MVStore store;
    private final boolean initial;

    public MVStoreCacheProvider(final Repository target) {
        final Path dbFile = target.getDirectory().toPath().resolve("cache.mv.db");
        initial = !Files.exists(dbFile);
        store = new MVStore.Builder()
                .fileName(dbFile.toString())
                .open();
    }

    @Override
    public boolean isInitial() {
        return initial;
    }

    @Override
    public Map<ObjectId, ObjectId> getCommitMapping() {
        final Marshaler<ObjectId> m = new Marshaler.ObjectIdMarshaler();
        return new MVMapAdapter<>(store.openMap("commits"), m, m);
    }

    @Override
    public Map<Entry, AnyColdEntry> getEntryMapping() {
        final Marshaler<Entry> km = new Marshaler.JavaSerializerMarshaler<>();
        final Marshaler<AnyColdEntry> vm = new Marshaler.JavaSerializerMarshaler<>();
        return new MVMapAdapter<>(store.openMap("entries"), km, vm);
    }

    @Override
    public Map<RefEntry, RefEntry> getRefEntryMapping() {
        final Marshaler<RefEntry> m = new Marshaler.JavaSerializerMarshaler<>();
        return new MVMapAdapter<>(store.openMap("refs"), m, m);
    }

    @Override
    public void close() {
        if (store != null && !store.isClosed()) {
            store.close();
        }
    }

    /**
     * Map adapter that serializes keys/values via Marshaler and stores them in an MVMap.
     */
    static class MVMapAdapter<K, V> extends AbstractMap<K, V> {
        private final MVMap<byte[], byte[]> map;
        private final Marshaler<K> keyMarshaler;
        private final Marshaler<V> valueMarshaler;

        MVMapAdapter(MVMap<byte[], byte[]> map, Marshaler<K> keyMarshaler, Marshaler<V> valueMarshaler) {
            this.map = map;
            this.keyMarshaler = keyMarshaler;
            this.valueMarshaler = valueMarshaler;
        }

        @Override
        public V get(final Object key) {
            @SuppressWarnings("unchecked")
            final K k = (K) key;
            final byte[] raw = map.get(keyMarshaler.marshal(k));
            return raw != null ? valueMarshaler.unmarshal(raw) : null;
        }

        @Override
        public V put(final K key, final V value) {
            map.put(keyMarshaler.marshal(key), valueMarshaler.marshal(value));
            return value;
        }

        @Override
        public Set<Map.Entry<K, V>> entrySet() {
            return map.entrySet().stream()
                    .map(e -> new SimpleEntry<>(keyMarshaler.unmarshal(e.getKey()), valueMarshaler.unmarshal(e.getValue())))
                    .collect(Collectors.toSet());
        }

        @Override
        public void clear() {
            map.clear();
        }
    }
}
