package jp.ac.titech.c.se.stein.core;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.field.DataType;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
import com.j256.ormlite.logger.Slf4jLoggingLogBackend;
import com.j256.ormlite.misc.TransactionManager;
import com.j256.ormlite.stmt.PreparedQuery;
import com.j256.ormlite.table.DatabaseTable;
import com.j256.ormlite.table.TableUtils;
import jp.ac.titech.c.se.stein.core.EntrySet.Entry;
import jp.ac.titech.c.se.stein.core.EntrySet.EntryList;
import org.apache.commons.lang3.NotImplementedException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public interface CacheProvider {
    /**
     * Save as mapping from {@code source} to {@code target}
     */
    void registerCommit(final ObjectId source, final ObjectId target, final Context c);

    /**
     * Save contents of given commitMapping.
     */
    default void writeOutFromCommitMapping(final Map<ObjectId, ObjectId> commitMapping, final Context c) throws IOException {
        commitMapping.forEach((source, target) -> registerCommit(source, target, c));
    }

    /**
     * Return corresponding target commit object to {@code source}
     *
     * @return Corresponding target commit object if exists, otherwise empty
     */
    Optional<ObjectId> getFromSourceCommit(final ObjectId source, final Context c);

    /**
     * Return all commit pairs
     *
     * @return Set of pairs of commits (left is one in source repository, right is corresponding one in target repository)
     */
    Set<Map.Entry<ObjectId, ObjectId>> getAllCommits(final Context c);

    /**
     * Return all commit pairs as commitMapping
     *
     * @return commitMapping consists of saved mapping
     */
    default Map<ObjectId, ObjectId> readToCommitMapping(final Context c) throws IOException {
        final Map<ObjectId, ObjectId> commitMapping = new HashMap<>();
        for (final Map.Entry<ObjectId, ObjectId> pair : getAllCommits(c)) {
            commitMapping.put(pair.getKey(), pair.getValue());
        }
        return commitMapping;
    }

    /**
     * Save as mapping from {@code source} to {@code target}
     */
    void registerEntry(final Entry source, final EntrySet target, final Context c);

    /**
     * Save contents of given entryMapping.
     */
    default void writeOutFromEntryMapping(final Map<Entry, EntrySet> entryMapping, final Context c) throws IOException {
        entryMapping.forEach((source, target) -> registerEntry(source, target, c));
    }

    /**
     * Return corresponding target entries to {@code source}
     *
     * @return Corresponding target commit object if exists, otherwise empty
     */
    Optional<EntrySet> getFromSourceEntry(final Entry source, final Context c);

    /**
     * Return all entry pairs
     *
     * @return List of pairs of entries (left is one in source repository, right is corresponding ones in target repository)
     */
    Set<Map.Entry<Entry, EntrySet>> getAllEntries(final Context c);

    /**
     * Return all entry pairs as entryMapping
     *
     * @return entryMapping consists of saved mapping
     */
    default Map<Entry, EntrySet> readToEntryMapping(final boolean concurrent, final Context c) throws IOException {
        final Map<Entry, EntrySet> entryMapping = concurrent ? new ConcurrentHashMap<>() : new HashMap<>();
        for (final Map.Entry<Entry, EntrySet> pair : getAllEntries(c)) {
            entryMapping.put(pair.getKey(), pair.getValue());
        }
        return entryMapping;
    }

    /**
     * Adapter for the commit mapping.
     */
    default Map<ObjectId, ObjectId> getCommitMapping(final Context c) {
        return new AbstractMap<>() {
            @Override
            public ObjectId get(final Object key) {
                return getFromSourceCommit((ObjectId) key, c).orElse(null);
            }

            @Override
            public ObjectId put(final ObjectId key, final ObjectId value) {
                registerCommit(key, value, c);
                return value;
            }

            @Override
            public Set<Entry<ObjectId, ObjectId>> entrySet() {
                return getAllCommits(c);
            }
        };
    }

    /**
     * Adapter for the entry mapping.
     */
    default Map<Entry, EntrySet> getEntryMapping(final Context c) {
        return new AbstractMap<>() {
            @Override
            public EntrySet get(final Object key) {
                return getFromSourceEntry((EntrySet.Entry) key, c).orElse(null);
            }

            @Override
            public EntrySet put(final EntrySet.Entry key, final EntrySet value) {
                registerEntry(key, value, c);
                return value;
            }

            @Override
            public Set<Entry<EntrySet.Entry, EntrySet>> entrySet() {
                return getAllEntries(c);
            }
        };
    }

    class SQLiteCacheProvider implements CacheProvider {
        static Logger log = LoggerFactory.getLogger(SQLiteCacheProvider.class);

        JdbcConnectionSource connectionSource = null;
        Dao<CommitMappingTable, String> commitMappingDao = null;
        Dao<EntryMappingTable, String> entryMappingDao = null;

        final Marshaler<EntrySet> marshaler = new Marshaler.JavaSerializerMarshaler<EntrySet>();

        public SQLiteCacheProvider(final Repository target) {
            com.j256.ormlite.logger.LoggerFactory.setLogBackendFactory(new Slf4jLoggingLogBackend.Slf4jLoggingLogBackendFactory());
            com.j256.ormlite.logger.Logger.setGlobalLogLevel(com.j256.ormlite.logger.Level.FATAL);

            final File dotGitDir = target.getDirectory().getAbsoluteFile();
            final Path dbFile = dotGitDir.toPath().resolve("cache.db");
            try {
                connectionSource = new JdbcConnectionSource("jdbc:sqlite:" + dbFile);
                commitMappingDao = DaoManager.createDao(connectionSource, CommitMappingTable.class);
                TableUtils.createTableIfNotExists(connectionSource, CommitMappingTable.class);
                entryMappingDao = DaoManager.createDao(connectionSource, EntryMappingTable.class);
                TableUtils.createTableIfNotExists(connectionSource, EntryMappingTable.class);
            } catch (final SQLException e) {
                log.error("Failed to connect to Database.", e);
            } finally {
                try {
                    if (connectionSource != null) {
                        connectionSource.close();
                    }
                } catch (final IOException e) {
                    log.error("Failed to close connection to Database.", e);
                }
            }
        }

        @DatabaseTable
        static class CommitMappingTable {
            @DatabaseField(uniqueCombo = true, uniqueIndexName = "mapping-ids")
            String sourceId;
            @DatabaseField(id = true, uniqueCombo = true, uniqueIndexName = "mapping-ids")
            String targetId;

            public CommitMappingTable() {}

            public CommitMappingTable(final ObjectId sourceId, final ObjectId targetId) {
                this.sourceId = sourceId.getName();
                this.targetId = targetId.getName();
            }
        }

        @DatabaseTable
        static class EntryMappingTable {
            @DatabaseField(uniqueCombo = true, uniqueIndexName = "mapping-ids", dataType = DataType.BYTE_ARRAY)
            byte[] source;
            @DatabaseField(id = true, uniqueCombo = true, uniqueIndexName = "mapping-ids", dataType = DataType.BYTE_ARRAY)
            byte[] target;

            public EntryMappingTable() {}

            public EntryMappingTable(final byte[] source, final byte[] target) {
                this.source = source;
                this.target = target;
            }
        }

        enum ObjectType {
            Commit, Tree, Blob
        }

        @Override
        public void registerCommit(final ObjectId source, final ObjectId target, final Context c) {
            try {
                TransactionManager.callInTransaction(connectionSource, (Callable<Void>) () -> {
                    final CommitMappingTable mapping = new CommitMappingTable(source, target);
                    commitMappingDao.create(mapping);
                    return null;
                });
            } catch (final SQLException e) {
                log.warn("Could not save mapping {} to {}", source.getName(), target.getName(), e);
            }
        }

        @Override
        public Optional<ObjectId> getFromSourceCommit(final ObjectId source, final Context c) {
            try {
                final PreparedQuery<CommitMappingTable> q = commitMappingDao.queryBuilder().where().eq("sourceId", source.getName()).prepare();
                return Optional.ofNullable(commitMappingDao.queryForFirst(q)).map(m -> ObjectId.fromString(m.targetId));
            } catch (final SQLException e) {
                log.warn("Could not fetch any data", e);
                return Optional.empty();
            }
        }

        @Override
        public void writeOutFromCommitMapping(final Map<ObjectId, ObjectId> commitMapping, final Context c) {
            try {
                entryMappingDao.callBatchTasks((Callable<Void>) () -> {
                    for (final Map.Entry<ObjectId, ObjectId> e : commitMapping.entrySet()) {
                        final CommitMappingTable m = new CommitMappingTable(e.getKey(), e.getValue());
                        commitMappingDao.createIfNotExists(m);
                    }
                    return null;
                });
            } catch (final Exception e) {
                log.warn("Failed to save", e);
            }
        }

        @Override
        public Set<Map.Entry<ObjectId, ObjectId>> getAllCommits(final Context c) {
            try {
                return commitMappingDao
                    .queryForAll()
                    .stream()
                    .map(m -> new AbstractMap.SimpleEntry<>(ObjectId.fromString(m.sourceId), ObjectId.fromString(m.targetId)))
                    .collect(Collectors.toSet());
            } catch (final SQLException e) {
                log.warn("Could not fetch any data", e);
                return Collections.emptySet();
            }
        }

        @Override
        public void registerEntry(final Entry source, final EntrySet target, final Context c) {
            if (source.isRoot()) {
                return;
            }
            try {
                TransactionManager.callInTransaction(connectionSource, (Callable<Void>) () -> {
                    final EntryMappingTable mapping = new EntryMappingTable(marshaler.marshal(source), marshaler.marshal(target));
                    entryMappingDao.createIfNotExists(mapping);
                    return null;
                });
            } catch (final SQLException e) {
                log.warn("Could not save mappings", e);
            }
        }

        @Override
        public void writeOutFromEntryMapping(final Map<Entry, EntrySet> entryMapping, final Context c) {
            try {
                entryMappingDao.callBatchTasks((Callable<Void>) () -> {
                    for (final Map.Entry<Entry, EntrySet> e : entryMapping.entrySet()) {
                        final Entry source = e.getKey();
                        final EntrySet target = e.getValue();
                        final EntryMappingTable mapping = new EntryMappingTable(marshaler.marshal(source), marshaler.marshal(target));
                        entryMappingDao.createIfNotExists(mapping);
                    }
                    return null;
                });
            } catch (final Exception e) {
                log.warn("Failed to save.", e);
            }
        }

        @Override
        public Optional<EntrySet> getFromSourceEntry(final Entry source, final Context c) {
            if (source.isRoot()) {
                return Optional.empty();
            }
            final EntryList el = new EntryList();
            try {
                final PreparedQuery<EntryMappingTable> q = entryMappingDao.queryBuilder().where().eq("source", marshaler.marshal(source)).prepare();
                return Optional.ofNullable(entryMappingDao.queryForFirst(q)).map(m -> marshaler.unmarshal(m.target));
            } catch (final SQLException e) {
                log.warn("Could not get any data", e);
                return Optional.empty();
            }
        }

        @Override
        public Set<Map.Entry<Entry, EntrySet>> getAllEntries(final Context c) {
            throw new NotImplementedException("This method should not be used.");
        }
    }
}
