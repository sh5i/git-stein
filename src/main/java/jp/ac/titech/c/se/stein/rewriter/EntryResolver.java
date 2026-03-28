package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyColdEntry;
import jp.ac.titech.c.se.stein.entry.Entry;

/**
 * Resolves an entry to its rewritten form, using caching to avoid redundant transformations.
 * This is the interface through which translators access child entries during tree rewriting.
 */
@FunctionalInterface
public interface EntryResolver {
    AnyColdEntry resolve(Entry entry, Context c);
}
