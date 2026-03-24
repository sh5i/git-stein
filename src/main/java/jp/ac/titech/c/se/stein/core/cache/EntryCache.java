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
    private final MVStore store;

    @Getter
    private final boolean initial;

    public EntryCache(final Repository target) {
        final Path dbFile = target.getDirectory().toPath().resolve("cache.mv.db");
        initial = !Files.exists(dbFile);
        store = new MVStore.Builder()
                .fileName(dbFile.toString())
                .autoCommitDisabled()
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
