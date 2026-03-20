/**
 * Provides the entry types used to represent Git tree entries throughout the rewriting pipeline.
 *
 * <h2>Two axes</h2>
 *
 * <p>The entry types are organized along two orthogonal axes:</p>
 *
 * <table>
 *   <caption>Entry type matrix</caption>
 *   <tr><th></th><th>Single</th><th>Any (0, 1, or many)</th></tr>
 *   <tr><td><b>Cold</b> (hash-based)</td><td>{@link Entry} (= ColdEntry)</td><td>{@link AnyColdEntry} ({@link Entry}, {@link AnyColdEntry.Set}, {@link AnyColdEntry.Empty})</td></tr>
 *   <tr><td><b>Hot</b> (data-bearing)</td><td>{@link HotEntry} ({@link HotEntry.SourceBlob}, {@link HotEntry.NewBlob})</td><td>{@link AnyHotEntry} ({@link HotEntry}, {@link AnyHotEntry.Set}, {@link AnyHotEntry.Empty})</td></tr>
 * </table>
 *
 * <h3>Cold vs Hot</h3>
 * <ul>
 *   <li><b>Cold</b> — references blob content by {@link org.eclipse.jgit.lib.ObjectId} (hash).
 *       Suitable for caching and serialization ({@link java.io.Serializable}).</li>
 *   <li><b>Hot</b> — holds or lazily loads the actual blob data ({@code byte[]}).
 *       Used during the rewriting pipeline when blob content needs to be read or transformed.</li>
 * </ul>
 *
 * <h3>Single vs Any</h3>
 * <ul>
 *   <li><b>Single</b> ({@link SingleEntry}) — exactly one tree entry.</li>
 *   <li><b>Any</b> — zero ({@code Empty}), one, or multiple ({@code Set}) entries.
 *       A single entry also implements its corresponding {@code Any} interface (singleton collection pattern),
 *       so {@link Entry} implements {@link AnyColdEntry} and {@link HotEntry} implements {@link AnyHotEntry}.</li>
 * </ul>
 *
 * @see SingleEntry
 * @see Entry
 * @see HotEntry
 * @see AnyColdEntry
 * @see AnyHotEntry
 */
package jp.ac.titech.c.se.stein.entry;
