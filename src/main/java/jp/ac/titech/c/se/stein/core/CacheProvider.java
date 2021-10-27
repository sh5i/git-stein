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
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class CacheProvider {
    private final static Logger log = LoggerFactory.getLogger(CacheProvider.class);

    static class KeyValue {
        @DatabaseField(id = true, dataType = DataType.BYTE_ARRAY)
        byte[] source;
        @DatabaseField(dataType = DataType.BYTE_ARRAY)
        byte[] target;
    }

    @DatabaseTable(tableName = "commits")
    static class CommitRow extends KeyValue {}

    @DatabaseTable(tableName = "entries")
    static class EntryRow extends KeyValue {}

    @DatabaseTable(tableName = "refs")
    static class RefRow extends KeyValue {}

    JdbcConnectionSource connectionSource = null;

    Dao<CommitRow, String> commitDao;

    Dao<EntryRow, String> entryDao;

    Dao<RefRow, String> refDao;

    public CacheProvider(final Repository target) {
        com.j256.ormlite.logger.LoggerFactory.setLogBackendFactory(new Slf4jLoggingLogBackend.Slf4jLoggingLogBackendFactory());
        com.j256.ormlite.logger.Logger.setGlobalLogLevel(com.j256.ormlite.logger.Level.FATAL);

        final File dotGitDir = target.getDirectory().getAbsoluteFile();
        final Path dbFile = dotGitDir.toPath().resolve("cache.db");
        try {
            connectionSource = new JdbcConnectionSource("jdbc:sqlite:" + dbFile);
            commitDao = DaoManager.createDao(connectionSource, CommitRow.class);
            TableUtils.createTableIfNotExists(connectionSource, CommitRow.class);
            entryDao = DaoManager.createDao(connectionSource, EntryRow.class);
            TableUtils.createTableIfNotExists(connectionSource, EntryRow.class);
            refDao = DaoManager.createDao(connectionSource, RefRow.class);
            TableUtils.createTableIfNotExists(connectionSource, RefRow.class);
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

    public Map<ObjectId, ObjectId> getCommitMapping() {
        final Marshaler<ObjectId> m = new Marshaler.ObjectIdMarshaler();
        return new SQLiteCache<>(commitDao, CommitRow::new, m, m);
    }

    public Map<Entry, EntrySet> getEntryMapping() {
        final Marshaler<Entry> km = new Marshaler.JavaSerializerMarshaler<>();
        final Marshaler<EntrySet> vm = new Marshaler.JavaSerializerMarshaler<>();
        return new SQLiteCache<>(entryDao, EntryRow::new, km, vm);
    }

    public Map<RefEntry, RefEntry> getRefEntryMapping() {
        final Marshaler<RefEntry> m = new Marshaler.JavaSerializerMarshaler<>();
        return new SQLiteCache<>(refDao, RefRow::new, m, m);
    }

    /**
     * Adapter for the commit mapping.
     */
    class SQLiteCache<K, V, Row extends KeyValue> extends AbstractMap<K, V> {

        final Dao<Row, String> dao;

        final Supplier<Row> constructor;

        final Marshaler<K> keyMarshaler;

        final Marshaler<V> valueMarshaler;

        public SQLiteCache(final Dao<Row, String> dao, final Supplier<Row> constructor, final Marshaler<K> keyMarshaler, Marshaler<V> valueMarshaler) {
            this.dao = dao;
            this.constructor = constructor;
            this.keyMarshaler = keyMarshaler;
            this.valueMarshaler = valueMarshaler;
        }

        @Override
        public V get(final Object key) {
            try {
                log.debug("Fetch from {}", dao.getTableName());
                final byte[] source = keyMarshaler.marshal((K) key);
                final PreparedQuery<Row> q = dao.queryBuilder().where().eq("source", source).prepare();
                final Row row = dao.queryForFirst(q);
                return row != null ? valueMarshaler.unmarshal(row.target) : null;
            } catch (final SQLException e) {
                log.warn("Could not fetch any data", e);
                return null;
            }
        }

        @Override
        public V put(final K key, final V value) {
            try {
                log.debug("Write to {}", dao.getTableName());
                TransactionManager.callInTransaction(connectionSource, (Callable<Void>) () -> {
                    final Row row = constructor.get();
                    row.source = keyMarshaler.marshal(key);
                    row.target = valueMarshaler.marshal(value);
                    dao.createIfNotExists(row);
                    return null;
                });
            } catch (final SQLException e) {
                log.warn("Could not save mappings", e);
            }
            return value;
        }

        @Override
        public Set<Entry<K, V>> entrySet() {
            try {
                return dao
                        .queryForAll()
                        .stream()
                        .map(r -> new AbstractMap.SimpleEntry<>(keyMarshaler.unmarshal(r.source), valueMarshaler.unmarshal(r.target)))
                        .collect(Collectors.toSet());
            } catch (final SQLException e) {
                log.warn("Could not fetch any data", e);
                return Collections.emptySet();
            }
        }

        @Override
        public void clear() {
            try {
                dao.deleteBuilder().delete();
            } catch (final SQLException e) {
                log.warn("Could not delete data", e);
            }
        }
    }
}
