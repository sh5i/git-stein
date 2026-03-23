package jp.ac.titech.c.se.stein.core.cache;

import jp.ac.titech.c.se.stein.core.RefEntry;

import com.google.common.cache.CacheBuilder;
import jp.ac.titech.c.se.stein.entry.AnyColdEntry;
import jp.ac.titech.c.se.stein.entry.Entry;
import org.eclipse.jgit.lib.ObjectId;

import java.util.Map;

/**
 * Non-persistent cache provider backed by Guava Cache with LRU eviction.
 */
public class GuavaCacheProvider implements CacheProvider {
    private static final double HEAP_FRACTION = 0.25;
    private static final int BYTES_PER_ENTRY = 300;

    private final long maxEntries;

    public GuavaCacheProvider() {
        final long budget = (long) (Runtime.getRuntime().maxMemory() * HEAP_FRACTION);
        this.maxEntries = Math.max(1000, budget / BYTES_PER_ENTRY);
    }

    @Override
    public boolean isInitial() {
        return true;
    }

    @Override
    public Map<ObjectId, ObjectId> getCommitMapping() {
        return CacheBuilder.newBuilder()
                .maximumSize(maxEntries)
                .<ObjectId, ObjectId>build()
                .asMap();
    }

    @Override
    public Map<Entry, AnyColdEntry> getEntryMapping() {
        return CacheBuilder.newBuilder()
                .maximumWeight(maxEntries)
                .weigher((Entry k, AnyColdEntry v) -> v.size())
                .<Entry, AnyColdEntry>build()
                .asMap();
    }

    @Override
    public Map<RefEntry, RefEntry> getRefEntryMapping() {
        return CacheBuilder.newBuilder()
                .maximumSize(maxEntries)
                .<RefEntry, RefEntry>build()
                .asMap();
    }
}
