package jp.ac.titech.c.se.stein.core;

import com.j256.ormlite.dao.CloseableIterator;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
import com.j256.ormlite.misc.TransactionManager;
import com.j256.ormlite.stmt.PreparedQuery;
import com.j256.ormlite.table.DatabaseTable;
import com.j256.ormlite.table.TableUtils;
import jp.ac.titech.c.se.stein.core.EntrySet.Entry;
import jp.ac.titech.c.se.stein.core.EntrySet.EntryList;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.notes.NoteMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public interface CacheProvider {

    // ObjectId系統

    /**
     * キャッシュを登録
     *
     * @param source 変換元オブジェクト
     * @param target 変換先オブジェクト
     */
    void registerObject(final ObjectId source, final ObjectId target, final Context c);

    /**
     * @param commitMapping 書き出し元のcommitMapping
     */
    default void writeOutFromCommitMapping(final Map<ObjectId, ObjectId> commitMapping, final Context c) throws IOException {
        commitMapping.forEach((source, target) -> registerObject(source, target, c));
        writeOut(c);
    }

    /**
     * キャッシュの取得
     *
     * @param source 取得したい対象のオブジェクト
     * @return sourceに紐付けられたデータがあればそのデータ、なければempty
     */
    Optional<ObjectId> getFromSourceObject(final ObjectId source, final Context c);

    Optional<ObjectId> getFromTargetObject(final ObjectId target, final Context c);

    /**
     * キャッシュ全件取得
     *
     * @return キャッシュのデータ(( sourceのID, targetのID)のペア)からなるList
     */
    List<ImmutablePair<ObjectId, ObjectId>> getAllObjects(final Context c);

    default Map<ObjectId, ObjectId> readToCommitMapping(final Context c) throws IOException {
        readIn(c);
        Map<ObjectId, ObjectId> commitMapping = new HashMap<>();
        for (ImmutablePair<ObjectId, ObjectId> pair : getAllObjects(c)) {
            commitMapping.put(pair.getLeft(), pair.getRight());
        }
        return commitMapping;
    }

    // EntrySet系統

    /**
     * @param source データを紐付けたいEntry
     * @param target 対象のEntrySet
     */
    void registerEntry(final Entry source, final EntrySet target, final Context c);

    default void writeOutFromEntryMapping(final Map<Entry, EntrySet> entryMapping, final Context c) throws IOException {
        entryMapping.forEach((source, target) -> {
            registerEntry(source, target, c);
        });
        writeOut(c);
    }

    Optional<EntrySet> getFromSourceEntry(final Entry source, final Context c);

    // 他のtargetも欲しくなりそうなのでPairで
    Optional<ImmutablePair<Entry, EntrySet>> getFromTargetEntry(final Entry target, final Context c);

    List<ImmutablePair<Entry, EntrySet>> getAllEntries(final Context c);

    // 遅延読み込みしたいと思うとこれは使わないかなあ……
    default Map<Entry, EntrySet> readToEntryMapping(final boolean concurrent, final Context c) throws IOException {
        readIn(c);
        Map<Entry, EntrySet> entryMapping = concurrent ? new ConcurrentHashMap<>() : new HashMap<>();
        for (ImmutablePair<Entry, EntrySet> pair : getAllEntries(c)) {
            entryMapping.put(pair.getLeft(), pair.getRight());
        }
        return entryMapping;
    }

    // 共通処理

    /**
     * キャッシュの書き出し
     *
     * @throws IOException 書き込みに失敗した場合
     */
    void writeOut(final Context c) throws IOException;

    /**
     * キャッシュの読み込み
     *
     * @throws IOException 読み込みに失敗した場合
     */
    void readIn(final Context c) throws IOException;

    public static class SQLiteCacheProvider implements CacheProvider {
        static Logger log = LoggerFactory.getLogger(SQLiteCacheProvider.class);

        // マルチスレッドにもできるらしい、要チェック
        JdbcConnectionSource connectionSource = null;
        Dao<Mapping, String> mappingDao = null;
        Dao<ObjectInfo, String> objectInfoDao = null;

        public SQLiteCacheProvider(Repository target) {
            File dotGitDir = target.getDirectory().getAbsoluteFile();
            Path dbFile = dotGitDir.toPath().resolve("cache.db");
            try {
                connectionSource = new JdbcConnectionSource("jdbc:sqlite:" + dbFile);
                mappingDao = DaoManager.createDao(connectionSource, Mapping.class);
                TableUtils.createTableIfNotExists(connectionSource, Mapping.class);
                objectInfoDao = DaoManager.createDao(connectionSource, ObjectInfo.class);
                TableUtils.createTableIfNotExists(connectionSource, ObjectInfo.class);
            } catch (SQLException e) {
                log.error("Failed to connect to Database.", e);
            } finally {
                try {
                    if (connectionSource != null) connectionSource.close();
                } catch (IOException e) {
                    log.error("Failed to close connection to Database.", e);
                }
            }
        }

        @DatabaseTable
        static class Mapping {
            // // sourceとtargetから計算できる値にするとよい?
            // @DatabaseField(generatedId = true)
            // UUID id;
            // primary keyだといいんだが……
            @DatabaseField(uniqueCombo = true, uniqueIndexName = "mapping-ids")
            String sourceId;
            // 応急処置(uniqueにならないかもしれない……)
            @DatabaseField(id = true, uniqueCombo = true, uniqueIndexName = "mapping-ids")
            String targetId;

            public Mapping() {}

            public Mapping(ObjectId sourceId, ObjectId targetId) {
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
            // 以下、typeがCommitの時nullable
            @DatabaseField
            int mode;
            @DatabaseField
            String fileName;
            @DatabaseField
            String directory;

            public ObjectInfo() {}

            public ObjectInfo(ObjectId id, ObjectType type) {
                this.id = id.getName();
                this.type = type;
            }

            public ObjectInfo(Entry e) {
                this.id = e.id.getName();
                this.type = e.isTree() ? ObjectType.Tree : ObjectType.Blob;
                this.fileName = e.name;
                this.directory = e.directory;
                this.mode = e.mode.getBits();
            }

            public ObjectInfo(ObjectId id, FileMode mode, String fileName, String directory) {
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
        public void registerObject(ObjectId source, ObjectId target, Context c) {
            try {
                TransactionManager.callInTransaction(connectionSource, (Callable<Void>) () -> {
                    Mapping mapping = new Mapping(source, target);
                    mappingDao.create(mapping);
                    ObjectInfo sourceObjInfo = new ObjectInfo(source, ObjectType.Commit);
                    objectInfoDao.createIfNotExists(sourceObjInfo);
                    ObjectInfo targetObjInfo = new ObjectInfo(target, ObjectType.Commit);
                    objectInfoDao.createIfNotExists(targetObjInfo);
                    return null;
                });
            } catch (SQLException e) {
                log.warn("Could not save mapping {} to {}", source.getName(), target.getName(), e);
            }
        }

        @Override
        public void writeOutFromCommitMapping(final Map<ObjectId, ObjectId> commitMapping, final Context c) throws IOException {
            try {
                mappingDao.callBatchTasks((Callable<Void>) () -> {
                    for (Map.Entry<ObjectId, ObjectId> e : commitMapping.entrySet()) {
                        Mapping m = new Mapping(e.getKey(), e.getValue());
                        mappingDao.createIfNotExists(m);
                        ObjectInfo sourceObjInfo = new ObjectInfo(e.getKey(), ObjectType.Commit);
                        objectInfoDao.createIfNotExists(sourceObjInfo);
                        ObjectInfo targetObjInfo = new ObjectInfo(e.getValue(), ObjectType.Commit);
                        objectInfoDao.createIfNotExists(targetObjInfo);
                    }
                    return null;
                });
            } catch (Exception e) {
                log.warn("Failed to save", e);
            }
            writeOut(c);
        }

        @Override
        public Optional<ObjectId> getFromSourceObject(ObjectId source, Context c) {
            try {
                PreparedQuery<Mapping> q = mappingDao.queryBuilder().where().eq("source", source.getName()).prepare();
                return Optional.ofNullable(mappingDao.queryForFirst(q)).map(m -> ObjectId.fromString(m.targetId));
            } catch (SQLException e) {
                log.warn("Could not fetch any data", e);
                return Optional.empty();
            }
        }

        @Override
        public Optional<ObjectId> getFromTargetObject(ObjectId target, Context c) {
            try {
                PreparedQuery<Mapping> q = mappingDao.queryBuilder().where().eq("target", target.getName()).prepare();
                return Optional.ofNullable(mappingDao.queryForFirst(q)).map(m -> ObjectId.fromString(m.sourceId));
            } catch (SQLException e) {
                log.warn("Could not fetch any data", e);
                return Optional.empty();
            }
        }

        @Override
        public List<ImmutablePair<ObjectId, ObjectId>> getAllObjects(Context c) {
            try {
                // ほんとかな(Commitだけとかあった方がよさ?)
                return mappingDao.queryForAll()
                    .stream()
                    .map(m -> ImmutablePair.of(ObjectId.fromString(m.sourceId), ObjectId.fromString(m.targetId)))
                    .collect(Collectors.toList());
            } catch (SQLException e) {
                log.warn("Could not fetch any data", e);
                return Collections.emptyList();
            }
        }

        @Override
        public void registerEntry(Entry source, EntrySet target, Context c) {
            try {
                if (target instanceof Entry) {
                    TransactionManager.callInTransaction(connectionSource, (Callable<Void>) () -> {
                        Mapping mapping = new Mapping(source.id, ((Entry) target).id);
                        mappingDao.createIfNotExists(mapping);
                        ObjectInfo sourceObjInfo = new ObjectInfo(source);
                        objectInfoDao.createIfNotExists(sourceObjInfo);
                        ObjectInfo targetObjInfo = new ObjectInfo((Entry) target);
                        objectInfoDao.createIfNotExists(targetObjInfo);
                        return null;
                    });
                } else {
                    TransactionManager.callInTransaction(connectionSource, (Callable<Void>) () -> {
                        ObjectInfo sourceInfo = new ObjectInfo(source);
                        objectInfoDao.createIfNotExists(sourceInfo);
                        for (Entry t : ((EntryList) target).entries()) {
                            Mapping mapping = new Mapping(source.id, t.id);
                            mappingDao.createIfNotExists(mapping);
                            ObjectInfo targetInfo = new ObjectInfo(t);
                            objectInfoDao.createIfNotExists(targetInfo);
                        }
                        return null;
                    });
                }
            } catch (SQLException e) {
                log.warn("Could not save mappings", e);
            }
        }

        @Override
        public Optional<EntrySet> getFromSourceEntry(Entry source, Context c) {
            EntryList el = new EntryList();
            try {
                PreparedQuery<Mapping> q = mappingDao.queryBuilder().where().eq("source", source.id.getName()).prepare();
                try (CloseableIterator<Mapping> mappings = mappingDao.iterator(q)) {
                    for (ObjectInfo t : objectInfoDao.query(objectInfoDao.queryBuilder().where().in("id", mappings).prepare())) {
                        if (t.type == ObjectType.Commit) continue;
                        el.add(t.toEntry());
                    }
                }
                return Optional.of(el);
            } catch (SQLException | IOException e) {
                log.warn("Could not get any data", e);
                return Optional.empty();
            }
        }

        @Override
        public Optional<ImmutablePair<Entry, EntrySet>> getFromTargetEntry(Entry target, Context c) {
            // 右はtarget自身も含める
            try {
                PreparedQuery<Mapping> q = mappingDao.queryBuilder().where().eq("target", target.id.getName()).prepare();
                Mapping m = mappingDao.queryForFirst(q);
                if (m == null) return Optional.empty();
                ObjectInfo sourceObjInfo = objectInfoDao.queryForId(m.sourceId);
                if (sourceObjInfo.type == ObjectType.Commit) return Optional.empty();

                Entry sourceEntry = sourceObjInfo.toEntry();
                EntrySet targetEntries = getFromSourceEntry(sourceEntry, c).orElse(EntrySet.EMPTY);
                return Optional.of(ImmutablePair.of(sourceEntry, targetEntries));
            } catch (SQLException e) {
                log.warn("Could not get any data", e);
                return Optional.empty();
            }
        }

        @Override
        public List<ImmutablePair<Entry, EntrySet>> getAllEntries(Context c) {
            // ?
            throw new NotImplementedException("This method should not be used.");
        }

        @Override
        public void writeOut(Context c) throws IOException {
            // ?
        }

        @Override
        public void readIn(Context c) throws IOException {
            // ?
        }
    }

    public static class GitNotesCacheProvider implements CacheProvider {
        protected static final String CACHE_REF = Constants.R_NOTES + "stein";

        protected NoteMap noteMap;
        protected RepositoryAccess targetRepo;

        static class EntrySerializer {
            StringBuilder ser;

            public EntrySerializer() {
                ser = new StringBuilder();
            }

            public EntrySerializer register(Entry e) {
                // ファイル名に,が入る可能性…… 普通にあるな
                ser.append(e.mode.getBits())
                    .append(':')
                    .append(e.name)
                    .append(':')
                    .append(e.id.getName())
                    .append(':')
                    .append(e.directory)
                    .append('\n');
                return this;
            }

            public String serialize() {
                return ser.toString();
            }
        }

        static class EntryDeserializer {
            public static ImmutablePair<Entry, EntrySet> deserialize(String s) {
                String[] items = s.split("\n");
                String[] sourceElms = items[0].split(":");
                Entry e = new Entry(
                    FileMode.fromBits(Integer.parseInt(sourceElms[0])),
                    sourceElms[1],
                    ObjectId.fromString(sourceElms[2]),
                    sourceElms[3]
                );
                EntryList el = new EntryList();
                Arrays.stream(items).skip(1).forEach(item -> {
                    String[] elms = item.split(":");
                    int modeBit = Integer.parseInt(elms[0]);
                    el.add(new Entry(
                        FileMode.fromBits(modeBit),
                        elms[1],
                        ObjectId.fromString(elms[2]),
                        elms[3]
                    ));
                });
                return ImmutablePair.of(e, el);
            }
        }

        public GitNotesCacheProvider(RepositoryAccess target) {
            this.targetRepo = target;
            this.noteMap = NoteMap.newEmptyMap();
        }

        @Override
        public void registerObject(final ObjectId source, final ObjectId target, final Context c) {
            synchronized (noteMap) {
                targetRepo.addNote(noteMap, target, source.getName(), c);
            }
        }

        @Override
        public Optional<ObjectId> getFromSourceObject(ObjectId source, Context c) {
            // これどーすんの
            return Optional.empty();
        }

        @Override
        public Optional<ObjectId> getFromTargetObject(ObjectId target, final Context c) {
            return Try.io(() -> Optional.ofNullable(noteMap.get(target)))
                .map(blobId -> ObjectId.fromString(targetRepo.readBlob(blobId, c), 0));
        }

        @Override
        public List<ImmutablePair<ObjectId, ObjectId>> getAllObjects(final Context c) {
            final List<ImmutablePair<ObjectId, ObjectId>> res = new ArrayList<>();
            targetRepo.eachNote(noteMap, (ObjectId targetCommitId, byte[] sourceCommitIdArray) -> {
                ObjectId sourceObjectId = ObjectId.fromString(sourceCommitIdArray, 0);
                res.add(ImmutablePair.of(sourceObjectId, targetCommitId));
            }, c);
            return res;
        }

        @Override
        public void registerEntry(Entry source, EntrySet target, Context c) {
            EntrySerializer es = new EntrySerializer().register(source);
            if (target instanceof Entry) {
                es.register((Entry) target);
                synchronized (noteMap) {
                    targetRepo.addNote(noteMap, ((Entry) target).id, es.serialize(), c);
                }
            } else {
                ((EntryList) target).entries().forEach(es::register);
                ((EntryList) target).entries().forEach(e -> {
                    synchronized (noteMap) {
                        targetRepo.addNote(noteMap, e.id, es.serialize(), c);

                    }
                });
            }

        }

        @Override
        public Optional<EntrySet> getFromSourceEntry(Entry source, Context c) {
            // これどーすんの
            return Optional.empty();
        }

        @Override
        public Optional<ImmutablePair<Entry, EntrySet>> getFromTargetEntry(Entry target, Context c) {
            return Try.io(() -> Optional.ofNullable(noteMap.get(target.id)))
                .map(blobId -> {
                    String data = new String(targetRepo.readBlob(blobId, c));
                    return EntryDeserializer.deserialize(data);
                });
        }

        @Override
        public List<ImmutablePair<Entry, EntrySet>> getAllEntries(Context c) {
            final HashMap<Entry, EntrySet> res = new HashMap<>();
            // 死ぬほど重複しそう……要素が一致すればhashCodeも一致するように実装されてるので一旦hashmapとかに入れる?

            targetRepo.eachNote(noteMap, (ObjectId targetCommitId, byte[] data) -> {
                ImmutablePair<Entry, EntrySet> pair = EntryDeserializer.deserialize(new String(data));
                res.putIfAbsent(pair.getLeft(), pair.getRight());
            }, c);
            return res.entrySet().stream().map(e -> ImmutablePair.of(e.getKey(), e.getValue())).collect(Collectors.toList());
        }


        @Override
        public void writeOut(final Context c) {
            // なんか工夫すればもうちょい速くなりそう
            targetRepo.writeNotes(noteMap, CACHE_REF, c);
        }

        @Override
        public void readIn(final Context c) {
            noteMap = targetRepo.readNote(CACHE_REF, c);
        }
    }
}
