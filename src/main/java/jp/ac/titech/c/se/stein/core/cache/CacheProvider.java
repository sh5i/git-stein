package jp.ac.titech.c.se.stein.core.cache;

import jp.ac.titech.c.se.stein.entry.AnyColdEntry;
import jp.ac.titech.c.se.stein.entry.Entry;

import java.util.Map;

/**
 * Common interface for cache providers that manage object mappings.
 */
public interface CacheProvider {
    boolean isInitial();

    Map<Entry, AnyColdEntry> getEntryMapping();

    default void close() {}
}
