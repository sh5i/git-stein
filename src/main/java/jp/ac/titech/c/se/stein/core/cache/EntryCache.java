package jp.ac.titech.c.se.stein.core.cache;

import jp.ac.titech.c.se.stein.entry.AnyColdEntry;
import jp.ac.titech.c.se.stein.entry.Entry;
import lombok.Getter;
import org.eclipse.jgit.lib.Repository;
import org.h2.mvstore.MVStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Persistent entry cache backed by H2 MVStore.
 * Data is stored in a single file ({@code cache.mv.db}) in the target repository's .git directory.
 */
public class EntryCache implements AutoCloseable {
    /**
     * Fraction of memoryBudget allocated to the read page cache.
     */
    private static final double READ_CACHE_RATIO = 1.0;

    /**
     * Fraction of memoryBudget allocated to the write buffer (auto-commit threshold).
     * Worst-case total memory usage is (READ_CACHE_RATIO + WRITE_BUFFER_RATIO) times the budget.
     */
    private static final double WRITE_BUFFER_RATIO = 0.5;

    private final MVStore store;

    @Getter
    private final boolean initial;

    public EntryCache(final Repository target, final long memoryBudget) {
        final Path dbFile = target.getDirectory().toPath().resolve("cache.mv.db");
        initial = !Files.exists(dbFile);
        final int cacheSizeMB = (int) Math.max(1, (long) (memoryBudget * READ_CACHE_RATIO) / (1024 * 1024));
        final int autoCommitBufferSizeKB = (int) Math.max(1, (long) (memoryBudget * WRITE_BUFFER_RATIO) / 1024);
        store = new MVStore.Builder()
                .fileName(dbFile.toString())
                .cacheSize(cacheSizeMB)
                .autoCommitBufferSize(autoCommitBufferSizeKB)
                .open();
    }

    @SuppressWarnings("unchecked")
    public Map<Entry, AnyColdEntry> getEntryMapping() {
        return store.openMap("entries");
    }

    @Override
    public void close() {
        if (store != null && !store.isClosed()) {
            store.commit();
            store.close();
        }
    }
}
