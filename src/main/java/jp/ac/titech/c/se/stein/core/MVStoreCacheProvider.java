package jp.ac.titech.c.se.stein.core;

import jp.ac.titech.c.se.stein.entry.AnyColdEntry;
import jp.ac.titech.c.se.stein.entry.Entry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.h2.mvstore.MVStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Cache provider backed by H2 MVStore.
 * Data is stored in a single file ({@code cache.mv.db}) in the target repository's .git directory.
 */
public class MVStoreCacheProvider implements CacheProvider {
    private final MVStore store;
    private final boolean initial;

    public MVStoreCacheProvider(final Repository target) {
        final Path dbFile = target.getDirectory().toPath().resolve("cache.mv.db");
        initial = !Files.exists(dbFile);
        store = new MVStore.Builder()
                .fileName(dbFile.toString())
                .autoCommitDisabled()
                .open();
    }

    @Override
    public boolean isInitial() {
        return initial;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<ObjectId, ObjectId> getCommitMapping() {
        return store.openMap("commits");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<Entry, AnyColdEntry> getEntryMapping() {
        return store.openMap("entries");
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<RefEntry, RefEntry> getRefEntryMapping() {
        return store.openMap("refs");
    }

    @Override
    public void close() {
        if (store != null && !store.isClosed()) {
            store.commit();
            store.close();
        }
    }
}
