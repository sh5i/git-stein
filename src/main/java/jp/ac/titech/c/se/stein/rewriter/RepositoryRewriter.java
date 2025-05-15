package jp.ac.titech.c.se.stein.rewriter;

import static java.nio.charset.StandardCharsets.US_ASCII;

import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import jp.ac.titech.c.se.stein.core.*;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.jgit.RevWalk;
import lombok.Setter;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.GpgSignature;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.Application.Config;
import jp.ac.titech.c.se.stein.core.Context.Key;
import jp.ac.titech.c.se.stein.entry.AnyColdEntry;

public class RepositoryRewriter implements RewriterCommand {
    private static final Logger log = LoggerFactory.getLogger(RepositoryRewriter.class);

    protected static final ObjectId ZERO = ObjectId.zeroId();

    /**
     * Entry-to-entries mapping.
     */
    protected Map<Entry, AnyColdEntry> entryMapping = new HashMap<>();

    /**
     * Root tree-to-tree mapping.
     */
    protected Map<ObjectId, ObjectId> rootTreeMapping = new HashMap<>();

    /**
     * Commit-to-commit mapping.
     */
    protected Map<ObjectId, ObjectId> commitMapping = new HashMap<>();

    /**
     * Tag-to-tag mapping.
     */
    protected final Map<ObjectId, ObjectId> tagMapping = new HashMap<>();

    /**
     * Ref-to-ref mapping.
     */
    protected Map<RefEntry, RefEntry> refEntryMapping = new HashMap<>();

    protected RepositoryAccess source, target;

    protected boolean isOverwriting = false;

    protected boolean isPathSensitive = false;

    @Setter
    protected Config config;

    public enum CacheLevel {
        blob, tree, commit
    }

    protected SQLiteCacheProvider cacheProvider;

    public void initialize(final Repository sourceRepo, final Repository targetRepo) {
        source = new RepositoryAccess(sourceRepo);
        target = new RepositoryAccess(targetRepo);
        isOverwriting = sourceRepo == targetRepo;
        if (config.nthreads > 1) {
            this.entryMapping = new ConcurrentHashMap<>();
        }
        if (config.isDryRunning) {
            source.setDryRunning(true);
            target.setDryRunning(true);
        }
        if (!config.cacheLevel.isEmpty()) {
            cacheProvider = new SQLiteCacheProvider(targetRepo);
            if (config.cacheLevel.contains(CacheLevel.commit)) {
                log.info("Stored mapping (commit-mapping) is available");
                commitMapping = new Cache<>(commitMapping, cacheProvider.getCommitMapping(), !cacheProvider.isInitial(), true);
                refEntryMapping = new Cache<>(refEntryMapping, cacheProvider.getRefEntryMapping(), !cacheProvider.isInitial(), true);
            }
            if (config.cacheLevel.contains(CacheLevel.blob) || config.cacheLevel.contains(CacheLevel.tree)) {
                log.info("Stored mapping (entry-mapping) is available");
                Map<Entry, AnyColdEntry> storedEntryMapping = cacheProvider.getEntryMapping();
                if (!config.cacheLevel.contains(CacheLevel.tree)) {
                    log.info("Stored mapping (entry-mapping): blob-only filtering");
                    storedEntryMapping = Cache.Filter.apply(e -> !e.isTree(), storedEntryMapping);
                } else if (!config.cacheLevel.contains(CacheLevel.blob)) {
                    log.info("Stored mapping (entry-mapping): tree-only filtering");
                    storedEntryMapping = Cache.Filter.apply(Entry::isTree, storedEntryMapping);
                }
                entryMapping = new Cache<>(entryMapping, storedEntryMapping, !cacheProvider.isInitial(), true);
            }
        }
    }

    public void rewrite(final Context c) {
        setUp(c);
        final RevWalk walk = prepareRevisionWalk(c);
        if (cacheProvider != null) {
            cacheProvider.inTransaction(() -> {
                rewriteCommits(walk, c);
                updateRefs(c);
                return null;
            });
        } else {
            if (config.nthreads >= 2) {
                log.debug("Parallel rewriting");
                rewriteRootTrees(walk, c);
                Try.io(walk::memoReset);
            }
            rewriteCommits(walk, c);
            updateRefs(c);
        }
        target.writeNotes(target.getDefaultNotes(), c);
        cleanUp(c);
    }

    protected void setUp(final Context c) {}

    /**
     * Rewrites all commits.
     */
    protected void rewriteCommits(final RevWalk walk, final Context c) {
        target.openInserter(ins -> {
            final Context uc = c.with(Key.inserter, ins);
            try (walk) {
                for (final RevCommit commit : walk) {
                    rewriteCommit(commit, uc);
                    commit.disposeBody();
                }
            }
        });
    }

    /**
     * Rewrites all root trees.
     */
    protected void rewriteRootTrees(final RevWalk walk, final Context c) {
        final Map<Long, Context> cxts = new ConcurrentHashMap<>();

        long count = 0;
        try (walk) {
            for (final RevCommit commit : walk) {
                count++;
            }
        }
        Try.io(walk::memoReset);

        try (walk) {
            final int characteristics = Spliterator.DISTINCT | Spliterator.IMMUTABLE | Spliterator.NONNULL | Spliterator.SIZED;
            final Spliterator<RevCommit> split = Spliterators.spliterator(walk.iterator(), count, characteristics);
            new ForkJoinPool(config.nthreads).submit(() -> {
                final Stream<RevCommit> stream = StreamSupport.stream(split, true);
                stream.forEach(commit -> {
                    final long id = Thread.currentThread().getId();
                    final Context uc = cxts.computeIfAbsent(id, k -> c.with(Key.inserter, target.getInserter()));
                    final Context uuc = uc.with(Key.rev, commit, Key.commit, commit);
                    rewriteRootTree(commit.getTree().getId(), uuc);
                });
            }).join();
        }

        // finalize
        for (final Context uc : cxts.values()) {
            uc.getInserter().close();
        }
    }

    /**
     * Prepares the revision walk.
     */
    protected RevWalk prepareRevisionWalk(final Context c) {
        final RevWalk walk = source.walk();
        Try.io(c, () -> {
            for (final ObjectId id : collectStarts(c)) {
                walk.memoMarkStart(id);
            }
            for (final ObjectId id : collectUninterestings(c)) {
                walk.memoMarkUninteresting(id);
            }
        });
        return walk;
    }

    /**
     * Collects the set of commit Ids used as start points.
     */
    protected Collection<ObjectId> collectStarts(final Context c) {
        final List<ObjectId> result = new ArrayList<>();
        for (final Ref ref : filterRefs(source.getRefs(), c)) {
            final ObjectId commitId = source.getRefTarget(ref);
            if (source.getObjectType(commitId) == Constants.OBJ_COMMIT) {
                log.debug("Ref {}: added as a start point (commit: {})", ref.getName(), commitId.name());
                result.add(commitId);
            } else {
                log.debug("Ref {}: non-commit; skipped (commit: {})", ref.getName(), commitId.name());
            }
        }
        return result;
    }

    /**
     * Confirms whether the given ref is used for a start point.
     */
    protected List<Ref> filterRefs(final List<Ref> refs, @SuppressWarnings("unused") final Context c) {
        return refs.stream()
                .filter(ref -> ref.getName().equals(Constants.HEAD) || ref.getName().startsWith(Constants.R_HEADS) || ref.getName().startsWith(Constants.R_TAGS))
                .collect(Collectors.toList());
    }

    /**
     * Collects the set of commit Ids used as uninteresting points.
     */
    protected Collection<ObjectId> collectUninterestings(@SuppressWarnings("unused") final Context c) {
        final List<ObjectId> result = new ArrayList<>();
        for (final Map.Entry<RefEntry, RefEntry> e : refEntryMapping.entrySet()) {
            final RefEntry ref = e.getKey();
            if (ref.id != null) {
                log.debug("Previous Ref {}: added as an uninteresting point (commit: {})", ref.name, ref.id.name());
                result.add(ref.id);
            }
        }
        refEntryMapping.clear();  // ref entries might be removed when updated.
        return result;
    }

    /**
     * Rewrites a commit.
     *
     * @param commit target commit.
     * @return the object ID of the rewritten commit
     */
    @SuppressWarnings("UnusedReturnValue")
    protected ObjectId rewriteCommit(final RevCommit commit, final Context c) {
        final Context uc = c.with(Key.rev, commit, Key.commit, commit);
        final ObjectId[] parentIds = rewriteParents(commit.getParents(), uc);
        final ObjectId treeId = rewriteRootTree(commit.getTree().getId(), uc);
        final PersonIdent author = rewriteAuthor(commit.getAuthorIdent(), uc);
        final PersonIdent committer = rewriteCommitter(commit.getCommitterIdent(), uc);
        final String msg = rewriteCommitMessage(commit.getFullMessage(), uc);
        ObjectId newId;
        if (config.isRewritingExtraAttributes) {
            final Charset enc = rewriteEncoding(commit.getEncoding(), uc);
            final GpgSignature sig = rewriteSignature(commit.getRawGpgSignature(), uc);
            newId = target.writeCommit(parentIds, treeId, author, committer, msg, enc, sig, uc);
        } else {
            newId = target.writeCommit(parentIds, treeId, author, committer, msg, uc);
        }

        final ObjectId oldId = commit.getId().copy();
        commitMapping.put(oldId, newId);
        log.debug("Rewrite commit: {} -> {} {}", oldId.name(), newId.name(), c);

        if (config.isAddingNotes) {
            target.addNote(target.getDefaultNotes(), newId, getNote(oldId, c), uc);
        }
        return newId;
    }

    /**
     * Returns a note for a commit.
     */
    protected byte[] getNote(final ObjectId oldCommitId, @SuppressWarnings("unused") final Context c) {
        final byte[] note = source.readNote(source.getDefaultNotes(), oldCommitId);
        if (note != null) {
            return note;
        }
        final byte[] blob = new byte[Constants.OBJECT_ID_STRING_LENGTH];
        oldCommitId.copyTo(blob, 0);
        return blob;
    }

    /**
     * Rewrites the parents of a commit.
     */
    protected ObjectId[] rewriteParents(final ObjectId[] parents, final Context c) {
        final ObjectId[] result = new ObjectId[parents.length];
        for (int i = 0; i < parents.length; i++) {
            final ObjectId parent = parents[i];
            final ObjectId newParent = commitMapping.get(parent);
            if (newParent == null) {
                log.warn("Parent commit has not rewritten yet: {} {}", parent.name(), c);
                result[i] = parent;
            } else {
                result[i] = newParent;
            }
        }
        return result;
    }

    /**
     * Rewrites the root tree of a commit.
     */
    protected ObjectId rewriteRootTree(final ObjectId treeId, final Context c) {
        final ObjectId cache = rootTreeMapping.get(treeId);
        if (cache != null) {
            return cache;
        }

        // A root tree is represented as a special entry whose name is "/"
        final Entry root = Entry.of(FileMode.TREE.getBits(), "", treeId, isPathSensitive ? "" : null);
        final AnyColdEntry newRoot = getEntry(root, c);
        final ObjectId newId = newRoot instanceof AnyColdEntry.Empty ? target.writeTree(Collections.emptyList(), c) : ((Entry) newRoot).id;

        log.debug("Rewrite root tree: {} -> {} {}", treeId.name(), newId.name(), c);
        rootTreeMapping.put(treeId, newId);
        return newId;
    }

    /**
     * Obtains tree entries from a tree entry.
     */
    protected AnyColdEntry getEntry(final Entry entry, final Context c) {
        // computeIfAbsent is unsuitable because this may be invoked recursively
        final AnyColdEntry cache = entryMapping.get(entry);
        if (cache != null) {
            return cache;
        }
        final AnyColdEntry result = rewriteEntry(entry, c);
        entryMapping.put(entry, result);
        return result;
    }

    /**
     * Rewrites a tree entry.
     */
    protected AnyColdEntry rewriteEntry(final Entry entry, final Context c) {
        final Context uc = c.with(Key.entry, entry);
        switch (entry.getType()) {
            case BLOB:
                final AnyColdEntry newBlob = rewriteBlobEntry(HotEntry.of(entry, source), uc).fold(target, uc);
                log.debug("Rewrite blob: {} -> {} {}", entry, newBlob, c);
                return newBlob;
            case TREE:
                final AnyColdEntry newTree = rewriteTreeEntry(entry, uc);
                log.debug("Rewrite tree: {} -> {} {}", entry, newTree, c);
                return newTree;
            case LINK:
                final AnyColdEntry newLink = rewriteLinkEntry(entry, uc);
                log.debug("Rewrite link: {} -> {} {}", entry, newLink, c);
                return newLink;
            default:
                assert false;
                return null;
        }
    }

    protected AnyHotEntry rewriteBlobEntry(HotEntry entry, Context c) {
        return entry;
    }

    protected AnyColdEntry rewriteTreeEntry(Entry entry, Context c) {
        final ObjectId newId = rewriteTree(entry.id, c);
        final String newName = rewriteName(entry.name, c);
        return newId == ZERO ? AnyColdEntry.empty() : Entry.of(entry.mode, newName, newId, entry.directory);
    }

    protected AnyColdEntry rewriteLinkEntry(Entry entry, Context c) {
        final ObjectId newId = rewriteLink(entry.id, c);
        final String newName = rewriteName(entry.name, c);
        return newId == ZERO ? AnyColdEntry.empty() : Entry.of(entry.mode, newName, newId, entry.directory);
    }

    /**
     * Rewrites a tree object.
     */
    protected ObjectId rewriteTree(final ObjectId treeId, final Context c) {
        final Entry entry = c.getEntry();
        final String path = entry.isRoot() ? "" : c.getPath() + "/" + entry.name;
        final Context uc = c.with(Key.path, path);

        final String dir = isPathSensitive ? path : null;

        final List<Entry> entries = new ArrayList<>();
        for (final Entry e : source.readTree(treeId, dir)) {
            final AnyColdEntry rewritten = getEntry(e, uc);
            rewritten.stream().forEach(entries::add);
        }
        final ObjectId newId = entries.isEmpty() ? ZERO : target.writeTree(entries, uc);
        if (log.isDebugEnabled() && !newId.equals(treeId)) {
            log.debug("Rewrite tree: {} -> {} {}", treeId.name(), newId.name(), c);
        }
        return newId;
    }

    /**
     * Rewrites a commit link.
     */
    protected ObjectId rewriteLink(final ObjectId commitId, @SuppressWarnings("unused") final Context c) {
        return commitId;
    }

    /**
     * Rewrites the name of a tree entry.
     */
    protected String rewriteName(final String name, final Context c) {
        return name;
    }

    /**
     * Rewrites the author identity of a commit.
     */
    protected PersonIdent rewriteAuthor(final PersonIdent author, final Context c) {
        return rewritePerson(author, c);
    }

    /**
     * Rewrites the committer identity of a commit.
     */
    protected PersonIdent rewriteCommitter(final PersonIdent committer, final Context c) {
        return rewritePerson(committer, c);
    }

    /**
     * Rewrites a person identity.
     */
    protected PersonIdent rewritePerson(final PersonIdent person, final Context c) {
        return person;
    }

    /**
     * Rewrites the message of a commit.
     */
    protected String rewriteCommitMessage(final String message, final Context c) {
        return rewriteMessage(message, c);
    }

    /**
     * Rewrites a message.
     */
    protected String rewriteMessage(final String message, @SuppressWarnings("unused") final Context c) {
        return message;
    }

    /**
     * Rewrites an encoding.
     */
    protected Charset rewriteEncoding(final Charset encoding, @SuppressWarnings("unused") final Context c) {
        return encoding;
    }

    /**
     * Rewrites a GPG signature.
     */
    protected GpgSignature rewriteSignature(final byte[] rawSignature, @SuppressWarnings("unused") final Context c) {
        if (rawSignature != null) {
            final String original = new String(rawSignature, US_ASCII);
            // TODO fixing the spacing. Why this is needed?
            final String fixed = original.replaceAll("\n ", "\n") + "\n";
            return new GpgSignature(fixed.getBytes(US_ASCII));
        } else {
            return null;
        }
    }

    /**
     * Updates ref objects.
     */
    protected void updateRefs(final Context c) {
        for (final Ref ref : filterRefs(source.getRefs(), c)) {
            updateRef(ref, c);
        }
    }

    /**
     * Updates a ref object.
     */
    protected RefEntry getRefEntry(final RefEntry entry, final Context c) {
        final RefEntry cache = refEntryMapping.get(entry);
        if (cache != null) {
            return cache;
        } else {
            final RefEntry result = rewriteRefEntry(entry, c);
            refEntryMapping.put(entry, result);
            return result;
        }
    }

    /**
     * Updates a ref object.
     */
    protected void updateRef(final Ref ref, final Context c) {
        final Context uc = c.with(Key.ref, ref);

        final RefEntry oldEntry = new RefEntry(ref);
        final RefEntry newEntry = getRefEntry(oldEntry, uc);
        if (newEntry == RefEntry.EMPTY) {
            // delete
            if (isOverwriting) {
                log.debug("Delete ref: {} {}", oldEntry, c);
                target.applyRefDelete(oldEntry);
            }
            return;
        }

        if (!oldEntry.name.equals(newEntry.name)) {
            // rename
            if (isOverwriting) {
                log.debug("Rename ref: {} -> {} {}", oldEntry.name, newEntry.name, c);
                target.applyRefRename(oldEntry.name, newEntry.name);
            }
        }

        final boolean linkEquals = Objects.equals(oldEntry.target, newEntry.target);
        final boolean idEquals = oldEntry.id == null ? newEntry.id == null : oldEntry.id.name().equals(newEntry.id.name());

        if (!isOverwriting || !linkEquals || !idEquals) {
            // update
            log.debug("Update ref: {} -> {} {}", oldEntry, newEntry, c);
            target.applyRefUpdate(newEntry);
        }
    }

    /**
     * Rewrites a ref entry.
     */
    protected RefEntry rewriteRefEntry(final RefEntry entry, final Context c) {
        if (entry.isSymbolic()) {
            final String newName = rewriteRefName(entry.name, c);

            final Ref targetRef = c.getRef().getTarget();
            final Context uc = c.with(Key.ref, targetRef);
            final String newTarget = getRefEntry(new RefEntry(targetRef), uc).name;
            return new RefEntry(newName, newTarget);
        } else {
            final String newName = rewriteRefName(entry.name, c);
            final int type = source.getObjectType(entry.id);
            final ObjectId newObjectId = rewriteRefObject(entry.id, type, c);
            return newObjectId == ZERO ? RefEntry.EMPTY : new RefEntry(newName, newObjectId);
        }
    }

    /**
     * Rewrites the referred object by a ref or a tag.
     */
    protected ObjectId rewriteRefObject(final ObjectId id, final int type, final Context c) {
        switch (type) {
            case Constants.OBJ_COMMIT: // 1: commit
                final ObjectId newCommitId = commitMapping.get(id);
                if (newCommitId == null) {
                    log.warn("Rewritten commit not found: {} {}", id.name(), c);
                    return id;
                }
                return newCommitId;

            case Constants.OBJ_TREE: // 2: tree
                // TODO rewriting opportunity for this tree
                final ObjectId newTreeId = source.copyTree(id, target, c);
                log.warn("A ref object tree {} found, just copied {}", id.name(), c);
                return newTreeId;

            case Constants.OBJ_BLOB: // 3: blob
                // TODO rewriting opportunity for this blob
                final ObjectId newBlobId = source.copyBlob(id, target, c);
                log.warn("A ref object blob {} found, just copied {}", id.name(), c);
                return newBlobId;

            case Constants.OBJ_TAG: // 4: tag
                final ObjectId newTagId = tagMapping.get(id);
                return newTagId != null ? newTagId : rewriteTag(source.parseTag(id), c);

            default:
                // referring non-commit and non-tag; ignore it
                log.warn("Ignore unknown type ({}): {} {}", type, id.name(), c);
                return id;
        }
    }

    /**
     * Rewrites a tag object.
     */
    protected ObjectId rewriteTag(final RevTag tag, final Context c) {
        final Context uc = c.with(Key.rev, tag, Key.tag, tag);
        final ObjectId oldId = tag.getId();

        final ObjectId oldObjectId = tag.getObject().getId();
        final int type = source.getObjectType(oldObjectId);
        final ObjectId newObjectId = rewriteRefObject(oldObjectId, type, uc);
        if (newObjectId == ZERO) {
            log.debug("Delete tag {} due to its object to be deleted {}", oldId, c);
            return ZERO;
        }
        log.debug("Rewrite tag object: {} -> {} {}", oldObjectId.name(), newObjectId.name(), c);

        final String tagName = tag.getTagName();
        final PersonIdent tagger = rewriteTagger(tag.getTaggerIdent(), tag, uc);
        final String message = rewriteTagMessage(tag.getFullMessage(), uc);
        final ObjectId newId = target.writeTag(newObjectId, type, tagName, tagger, message, uc);
        log.debug("Rewrite tag: {} -> {} {}", oldId.name(), newId.name(), c);

        tagMapping.put(oldId, newId);
        return newId;
    }

    /**
     * Rewrites the tagger identity of a tag.
     */
    protected PersonIdent rewriteTagger(final PersonIdent tagger, @SuppressWarnings("unused") final RevTag tag, final Context c) {
        return rewritePerson(tagger, c);
    }

    /**
     * Rewrites the message of a tag.
     */
    protected String rewriteTagMessage(final String message, final Context c) {
        return rewriteMessage(message, c);
    }

    /**
     * Rewrites a ref name.
     */
    protected String rewriteRefName(final String name, final Context c) {
        if (name.startsWith(Constants.R_HEADS)) {
            final String branchName = name.substring(Constants.R_HEADS.length());
            return Constants.R_HEADS + rewriteBranchName(branchName, c);
        } else if (name.startsWith(Constants.R_TAGS)) {
            final String tagName = name.substring(Constants.R_TAGS.length());
            return Constants.R_TAGS + rewriteTagName(tagName, c);
        } else {
            return name;
        }
    }

    /**
     * Rewrites a local branch name.
     */
    protected String rewriteBranchName(final String name, final Context c) {
        return name;
    }

    /**
     * Rewrites a tag name.
     */
    protected String rewriteTagName(final String name, final Context c) {
        return name;
    }

    /**
     * A hook method for cleaning up.
     */
    protected void cleanUp(@SuppressWarnings("unused") final Context c) {}

    @Override
    public String toString() {
        return getClass().getName();
    }

    public interface Factory extends RewriterCommand {
        RepositoryRewriter create();
    }
}
