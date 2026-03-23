package jp.ac.titech.c.se.stein.core.cache;

import jp.ac.titech.c.se.stein.core.RefEntry;

import jp.ac.titech.c.se.stein.entry.AnyColdEntry;
import jp.ac.titech.c.se.stein.entry.Entry;
import org.eclipse.jgit.lib.ObjectId;

import java.util.Map;

/**
 * Common interface for cache providers that manage object mappings.
 */
public interface CacheProvider {
    boolean isInitial();

    Map<ObjectId, ObjectId> getCommitMapping();

    Map<Entry, AnyColdEntry> getEntryMapping();

    Map<RefEntry, RefEntry> getRefEntryMapping();

    default void inTransaction(java.util.concurrent.Callable<Void> fn) {
        try {
            fn.call();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    default void close() {}
}
