package jp.ac.titech.c.se.stein.core;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
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
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
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
        writeOut(c);
    }

    /**
     * Return corresponding target commit object to {@code source}
     *
     * @return Corresponding target commit object if exists, otherwise empty
     */
    Optional<ObjectId> getFromSourceCommit(final ObjectId source, final Context c);

    /**
     * Return corresponding target commit object to {@code target}
     *
     * @return Corresponding target commit object if exists, otherwise empty
     */
    Optional<ObjectId> getFromTargetCommit(final ObjectId target, final Context c);

    /**
     * Return all commit pairs
     *
     * @return List of pairs of commits (left is one in source repository, right is corresponding one in target repository)
     */
    Collection<Pair<ObjectId, ObjectId>> getAllCommits(final Context c);

    /**
     * Return all commit pairs as commitMapping
     *
     * @return commitMapping consists of saved mapping
     */
    default Map<ObjectId, ObjectId> readToCommitMapping(final Context c) throws IOException {
        readIn(c);
        final Map<ObjectId, ObjectId> commitMapping = new HashMap<>();
        for (final Pair<ObjectId, ObjectId> pair : getAllCommits(c)) {
            commitMapping.put(pair.getLeft(), pair.getRight());
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
        writeOut(c);
    }

    /**
     * Return corresponding target entries to {@code source}
     *
     * @return Corresponding target commit object if exists, otherwise empty
     */
    Optional<EntrySet> getFromSourceEntry(final Entry source, final Context c);

    /**
     * Return entry mapping including {@code target} as target entry
     *
     * @return Pair of entry mapping (left is source, right is targets) if {@code target} is saved as target entry, otherwise empty.
     */
    Optional<Pair<Entry, EntrySet>> getFromTargetEntry(final Entry target, final Context c);

    /**
     * Return all entry pairs
     *
     * @return List of pairs of entries (left is one in source repository, right is corresponding ones in target repository)
     */
    Collection<Pair<Entry, EntrySet>> getAllEntries(final Context c);

    /**
     * Return all entry pairs as entryMapping
     *
     * @return entryMapping consists of saved mapping
     */
    default Map<Entry, EntrySet> readToEntryMapping(final boolean concurrent, final Context c) throws IOException {
        readIn(c);
        final Map<Entry, EntrySet> entryMapping = concurrent ? new ConcurrentHashMap<>() : new HashMap<>();
        for (final Pair<Entry, EntrySet> pair : getAllEntries(c)) {
            entryMapping.put(pair.getLeft(), pair.getRight());
        }
        return entryMapping;
    }

    /**
     * Some operations need to finish saving
     */
    void writeOut(final Context c) throws IOException;

    /**
     * Some operation need to finish loading
     */
    void readIn(final Context c) throws IOException;

    class SQLiteCacheProvider implements CacheProvider {
        static Logger log = LoggerFactory.getLogger(SQLiteCacheProvider.class);

        JdbcConnectionSource connectionSource = null;
        Dao<CommitMappingTable, String> commitMappingDao = null;
        Dao<EntryMappingTable, String> entryMappingDao = null;
        Dao<ObjectInfo, String> objectInfoDao = null;

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
                objectInfoDao = DaoManager.createDao(connectionSource, ObjectInfo.class);
                TableUtils.createTableIfNotExists(connectionSource, ObjectInfo.class);
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
            @DatabaseField(uniqueCombo = true, uniqueIndexName = "mapping-ids")
            String sourceId;
            @DatabaseField(id = true, uniqueCombo = true, uniqueIndexName = "mapping-ids")
            String targetId;

            public EntryMappingTable() {}

            public EntryMappingTable(final ObjectId sourceId, final ObjectId targetId) {
                this.sourceId = sourceId.getName();
                this.targetId = targetId.getName();
            }
        }

        enum ObjectType {
            Commit, Tree, Blob
        }

        @DatabaseTable
        static class ObjectInfo {
            @DatabaseField(id = true)
            String id;
            @DatabaseField
            ObjectType type;
            @DatabaseField
            int mode;
            @DatabaseField
            String fileName;
            @DatabaseField
            String directory;

            public ObjectInfo() {}

            public ObjectInfo(final ObjectId id, final ObjectType type) {
                this.id = id.getName();
                this.type = type;
            }

            public ObjectInfo(final Entry e) {
                this.id = e.id.getName();
                this.type = e.isTree() ? ObjectType.Tree : ObjectType.Blob;
                this.fileName = e.name;
                this.directory = e.directory;
                this.mode = e.mode.getBits();
            }

            public ObjectInfo(final ObjectId id, final FileMode mode, final String fileName, final String directory) {
                this.id = id.getName();
                this.mode = mode.getBits();
                this.fileName = fileName;
                this.directory = directory;
            }

            public FileMode getFileMode() {
                return FileMode.fromBits(this.mode);
            }

            public Entry toEntry() {
                return new Entry(getFileMode(), fileName, ObjectId.fromString(id), directory);
            }

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
        public Optional<ObjectId> getFromTargetCommit(final ObjectId target, final Context c) {
            try {
                final PreparedQuery<CommitMappingTable> q = commitMappingDao.queryBuilder().where().eq("targetId", target.getName()).prepare();
                return Optional.ofNullable(commitMappingDao.queryForFirst(q)).map(m -> ObjectId.fromString(m.sourceId));
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
            writeOut(c);
        }

        @Override
        public Collection<Pair<ObjectId, ObjectId>> getAllCommits(final Context c) {
            try {
                return commitMappingDao
                    .queryForAll()
                    .stream()
                    .map(m -> ImmutablePair.of(ObjectId.fromString(m.sourceId), ObjectId.fromString(m.targetId)))
                    .collect(Collectors.toList());
            } catch (final SQLException e) {
                log.warn("Could not fetch any data", e);
                return Collections.emptyList();
            }
        }

        @Override
        public void registerEntry(final Entry source, final EntrySet target, final Context c) {
            if (source.isRoot()) {
                return;
            }
            try {
                if (target instanceof Entry) {
                    TransactionManager.callInTransaction(connectionSource, (Callable<Void>) () -> {
                        final EntryMappingTable mapping = new EntryMappingTable(source.id, ((Entry) target).id);
                        entryMappingDao.createIfNotExists(mapping);
                        final ObjectInfo sourceObjInfo = new ObjectInfo(source);
                        objectInfoDao.createIfNotExists(sourceObjInfo);
                        final ObjectInfo targetObjInfo = new ObjectInfo((Entry) target);
                        objectInfoDao.createIfNotExists(targetObjInfo);
                        return null;
                    });
                } else if (target instanceof EntryList) {
                    TransactionManager.callInTransaction(connectionSource, (Callable<Void>) () -> {
                        final ObjectInfo sourceInfo = new ObjectInfo(source);
                        objectInfoDao.createIfNotExists(sourceInfo);
                        for (final Entry t : ((EntryList) target).entries()) {
                            final EntryMappingTable mapping = new EntryMappingTable(source.id, t.id);
                            entryMappingDao.createIfNotExists(mapping);
                            final ObjectInfo targetInfo = new ObjectInfo(t);
                            objectInfoDao.createIfNotExists(targetInfo);
                        }
                        return null;
                    });
                }
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
                        if (e.getValue() instanceof Entry) {
                            final Entry target = (Entry) e.getValue();
                            final EntryMappingTable mapping = new EntryMappingTable(source.id, target.id);
                            entryMappingDao.createIfNotExists(mapping);
                            final ObjectInfo sourceObjInfo = new ObjectInfo(source);
                            objectInfoDao.createIfNotExists(sourceObjInfo);
                            final ObjectInfo targetObjInfo = new ObjectInfo(target);
                            objectInfoDao.createIfNotExists(targetObjInfo);
                        } else if (e.getValue() instanceof EntryList) {
                            final ObjectInfo sourceObjInfo = new ObjectInfo(source);
                            objectInfoDao.createIfNotExists(sourceObjInfo);
                            for (final Entry t : ((EntryList) e.getValue()).entries()) {
                                final EntryMappingTable mapping = new EntryMappingTable(source.id, t.id);
                                entryMappingDao.createIfNotExists(mapping);
                                final ObjectInfo targetObjectInfo = new ObjectInfo(t);
                                objectInfoDao.createIfNotExists(targetObjectInfo);
                            }
                        }
                    }
                    return null;
                });
            } catch (final Exception e) {
                log.warn("Failed to save.", e);
            }
            writeOut(c);
        }

        @Override
        public Optional<EntrySet> getFromSourceEntry(final Entry source, final Context c) {
            if (source.isRoot()) {
                return Optional.empty();
            }
            final EntryList el = new EntryList();
            try {
                final PreparedQuery<EntryMappingTable> q = entryMappingDao.queryBuilder().where().eq("sourceId", source.id.getName()).prepare();
                final Collection<String> mappings = entryMappingDao.query(q).stream().map(entry -> entry.sourceId).collect(Collectors.toList());
                for (final ObjectInfo t : objectInfoDao.query(objectInfoDao.queryBuilder().where().in("id", mappings).prepare())) {
                    el.add(t.toEntry());
                }
                if (el.entries().size() == 0) {
                    return Optional.empty();
                } else if (el.entries().size() == 1) {
                    return Optional.of(el.entries().get(0));
                } else {
                    return Optional.of(el);
                }
            } catch (final SQLException e) {
                log.warn("Could not get any data", e);
                return Optional.empty();
            }
        }

        @Override
        public Optional<Pair<Entry, EntrySet>> getFromTargetEntry(final Entry target, final Context c) {
            try {
                final PreparedQuery<EntryMappingTable> q = entryMappingDao.queryBuilder().where().eq("targetId", target.id.getName()).prepare();
                final EntryMappingTable m = entryMappingDao.queryForFirst(q);
                if (m == null) {
                    return Optional.empty();
                }
                final ObjectInfo sourceObjInfo = objectInfoDao.queryForId(m.sourceId);
                if (sourceObjInfo.type == ObjectType.Commit) {
                    return Optional.empty();
                }

                final Entry sourceEntry = sourceObjInfo.toEntry();
                final Optional<EntrySet> targetEntries = getFromSourceEntry(sourceEntry, c);
                return targetEntries.map(entrySet -> ImmutablePair.of(sourceEntry, entrySet));
            } catch (final SQLException e) {
                log.warn("Could not get any data", e);
                return Optional.empty();
            }
        }

        @Override
        public Collection<Pair<Entry, EntrySet>> getAllEntries(final Context c) {
            throw new NotImplementedException("This method should not be used.");
        }

        @Override
        public void writeOut(final Context c) {
        }

        @Override
        public void readIn(final Context c) {
        }
    }
}
