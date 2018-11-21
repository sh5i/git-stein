package jp.ac.titech.c.se.stein;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.SymbolicRef;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevSort;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.Entry.SingleEntry;
import jp.ac.titech.c.se.stein.Try.ThrowableFunction;

public class RepositoryRewriter extends RepositoryAccess {
    private static final Logger log = LoggerFactory.getLogger(RepositoryRewriter.class);

    private static final ObjectId ZERO = ObjectId.zeroId();

    protected final Map<ObjectId, ObjectId> commitMapping = new HashMap<>();

    protected Map<SingleEntry, Entry> entryMapping = new HashMap<>();

    protected ObjectInserter inserter = null;

    protected boolean pathSensitive = false;

    public void rewrite() {
        rewriteCommits();
        updateRefs();
    }

    /**
     * Sets whether entries are path-sensitive.
     */
    protected void setPathSensitive(final boolean value) {
        this.pathSensitive = value;
    }

    /**
     * Rewrites all commits.
     */
    protected void rewriteCommits() {
        try (final ObjectInserter ins = writeRepo.newObjectInserter()) {
            this.inserter = ins;
            try (final RevWalk walk = prepareRevisionWalk()) {
                for (final RevCommit c : walk) {
                    rewriteCommit(c);
                }
            }
            this.inserter = null;
        }
    }

    /**
     * Prepares the revision walk.
     */
    protected RevWalk prepareRevisionWalk() {
        final Collection<ObjectId> starts = collectStarts();
        final Collection<ObjectId> uninterestings = collectUninterestings();

        final RevWalk walk = new RevWalk(repo);
        Try.io(() -> {
            for (final ObjectId id : starts) {
                walk.markStart(walk.parseCommit(id));
            }
            for (final ObjectId id : uninterestings) {
                walk.markUninteresting(walk.parseCommit(id));
            }
        });

        walk.sort(RevSort.TOPO, true);
        walk.sort(RevSort.REVERSE, true);
        return walk;
    }

    /**
     * Collects the set of commit Ids used as start points.
     */
    protected Collection<ObjectId> collectStarts() {
        final List<ObjectId> result = new ArrayList<>();
        final List<Ref> refs = Try.io(() -> repo.getRefDatabase().getRefs());
        for (final Ref ref : refs) {
            if (confirmStartRef(ref)) {
                final ObjectId commitId = specifyCommit(ref);
                log.debug("Add start point: {} (specified by {})", commitId.name(), ref.getName());
                result.add(specifyCommit(ref));
            }
        }
        return result;
    }

    /**
     * Confirms whether the given ref is used for a start point.
     */
    protected boolean confirmStartRef(final Ref ref) {
        final String name = ref.getName();
        return name.equals(Constants.HEAD) || name.startsWith(Constants.R_HEADS) || name.startsWith(Constants.R_TAGS);
    }

    /**
     * Collects the set of commit Ids used as uninteresting points.
     */
    protected Collection<ObjectId> collectUninterestings() {
        @SuppressWarnings("unchecked")
        final List<ObjectId> result = Collections.EMPTY_LIST;
        return result;
    }

    /**
     * Rewrites a commit.
     *
     * @param commit
     *            target commit.
     * @return the object ID of the rewritten commit
     */
    protected ObjectId rewriteCommit(final RevCommit commit) {
        final CommitBuilder builder = new CommitBuilder();
        builder.setParentIds(rewriteParents(commit.getParents()));
        builder.setTreeId(rewriteRootTree(commit.getTree().getId()));
        builder.setAuthor(rewriteAuthor(commit.getAuthorIdent(), commit));
        builder.setCommitter(rewriteCommitter(commit.getCommitterIdent(), commit));
        builder.setMessage(rewriteCommitMessage(commit.getFullMessage(), commit));
        final ObjectId newId = tryInsert((i) -> i.insert(builder));

        final ObjectId oldId = commit.getId();
        commitMapping.put(oldId, newId);

        log.debug("Rewrite commit: {} -> {}", oldId.name(), newId.name());
        return newId;
    }

    /**
     * Rewrites the parents of a commit.
     */
    protected ObjectId[] rewriteParents(final ObjectId[] parents) {
        final ObjectId[] result = new ObjectId[parents.length];
        for (int i = 0; i < parents.length; i++) {
            result[i] = commitMapping.get(parents[i]);
        }
        return result;
    }

    /**
     * Rewrites the root tree of a commit.
     */
    protected ObjectId rewriteRootTree(final ObjectId treeId) {
        // A root tree is represented as a special entry whose name is "/"
        final SingleEntry root = Entry.of(FileMode.TREE, "/", treeId, pathSensitive ? "" : null);
        final Entry newRoot = getEntry(root);
        final ObjectId newId = newRoot == Entry.EMPTY ? writeTree(Entry.EMPTY_ENTRIES) : ((SingleEntry) newRoot).id;

        log.debug("Rewrite tree: {} -> {}", treeId.name(), newId.name());
        return newId;
    }

    /**
     * Obtains tree entries from a tree entry.
     */
    protected Entry getEntry(final SingleEntry entry) {
        // computeIfAbsent is unsuitable because this may be invoked recursively
        final Entry cache = entryMapping.get(entry);
        if (cache != null) {
            return cache;
        } else {
            final Entry result = rewriteEntry(entry);
            entryMapping.put(entry, result);
            return result;
        }
    }

    /**
     * Rewrites a tree entry.
     */
    protected Entry rewriteEntry(final SingleEntry entry) {
        final ObjectId newId = entry.isTree() ? rewriteTree(entry.id, entry) : rewriteBlob(entry.id, entry);
        final String newName = rewriteName(entry.name, entry);
        return newId == ZERO ? Entry.EMPTY : Entry.of(entry.mode, newName, newId, entry.pathContext);
    }

    /**
     * Rewrites a tree object.
     */
    protected ObjectId rewriteTree(final ObjectId treeId, final SingleEntry entry) {
        final List<SingleEntry> entries = new ArrayList<>();
        for (final SingleEntry e : readTree(treeId, pathSensitive ? entry.pathContext + "/" + entry.name : null)) {
            final Entry rewritten = getEntry(e);
            rewritten.registerTo(entries);
        }
        return entries.isEmpty() ? ZERO : writeTree(entries);
    }

    /**
     * Rewrites a blob object.
     */
    protected ObjectId rewriteBlob(final ObjectId blobId, final SingleEntry entry) {
        return blobId;
    }

    /**
     * Rewrites the name of a tree entry.
     */
    protected String rewriteName(final String name, final SingleEntry entry) {
        return name;
    }

    /**
     * Rewrites the author identity of a commit.
     */
    protected PersonIdent rewriteAuthor(final PersonIdent author, final RevCommit commit) {
        return rewritePerson(author);
    }

    /**
     * Rewrites the committer identity of a commit.
     */
    protected PersonIdent rewriteCommitter(final PersonIdent committer, final RevCommit commit) {
        return rewritePerson(committer);
    }

    /**
     * Rewrites a person identity.
     */
    protected PersonIdent rewritePerson(final PersonIdent person) {
        return person;
    }

    /**
     * Rewrites the message of a commit.
     */
    protected String rewriteCommitMessage(final String message, final RevCommit commit) {
        return rewriteMessage(message, commit.getId());
    }

    /**
     * Rewrites a message.
     */
    protected String rewriteMessage(final String message, final ObjectId id) {
        return "orig:" + id.name() + " " + message;
    }

    /**
     * Updates ref objects.
     */
    protected void updateRefs() {
        final List<Ref> refs = Try.io(() -> repo.getRefDatabase().getRefs());
        for (final Ref ref : refs) {
            if (confirmUpdateRef(ref)) {
                updateRef(ref);
            }
        }
    }

    /**
     * Confirms whether the given ref is to be updated.
     */
    protected boolean confirmUpdateRef(final Ref ref) {
        return confirmStartRef(ref);
    }

    /**
     * Updates a ref object.
     */
    protected void updateRef(final Ref ref) {
        if (ref.isSymbolic()) {
            updateSymbolicRef((SymbolicRef) ref);
            updateRefName(ref);
        } else {
            final Ref peeled = Try.io(() -> repo.getRefDatabase().peel(ref));
            final ObjectId newId;
            if (peeled.getPeeledObjectId() != null) {
                // tag
                final RevTag tag;
                try (final RevWalk walk = new RevWalk(repo)) {
                    tag = Try.io(() -> walk.parseTag(ref.getObjectId()));
                }
                newId = rewriteTagObject(ref.getObjectId(), tag, ref);
            } else {
                newId = rewriteRefTarget(ref.getObjectId(), ref);
            }
            writeRefTarget(ref, newId);
            if (newId != ZERO) {
                updateRefName(ref);
            }
        }
    }

    /**
     * Updates symbolic ref object.
     */
    protected void updateSymbolicRef(final SymbolicRef ref) {
        final Ref target = ref.getTarget();
        final String name = target.getName();
        final String newName = rewriteRefName(name, target.getTarget());
        if (!name.equals(newName)) {
            // update symbolic ref target
            log.debug("Update ref target ({}): {} -> {}", ref.getName(), name, newName);
            Try.io(() -> {
                final RefUpdate update = repo.getRefDatabase().newUpdate(ref.getName(), false);
                update.setForceUpdate(true);
                update.link(newName);
                update.setNewObjectId(rewriteRefTarget(ref.getObjectId(), ref));
                update.forceUpdate();
            });
        }
    }

    /**
     * Overwrites ref target.
     */
    protected void writeRefTarget(final Ref ref, final ObjectId newId) {
        final ObjectId oldId = ref.getObjectId();
        if (newId == ZERO) {
            log.debug("Delete ref ({}): {} -> {}", ref.getName(), oldId.name(), newId.name());
            Try.io(() -> {
                final RefUpdate update = repo.getRefDatabase().newUpdate(ref.getName(), true);
                update.setForceUpdate(true);
                update.delete();
            });
        } else if (!newId.equals(oldId)) {
            log.debug("Update ref target ({}): {} -> {}", ref.getName(), oldId.name(), newId.name());
            Try.io(() -> {
                final RefUpdate update = repo.getRefDatabase().newUpdate(ref.getName(), true);
                update.setForceUpdate(true);
                update.setNewObjectId(newId);
                update.forceUpdate();
            });
        }
    }

    /**
     * Updates the target object of a ref.
     */
    protected ObjectId rewriteRefTarget(final ObjectId id, final Ref ref) {
        final ObjectId result = commitMapping.get(id);
        return result != null ? result : id;
    }

    /**
     * Rewrites a tag object.
     */
    protected ObjectId rewriteTagObject(final ObjectId tagId, final RevTag tag, final Ref ref) {
        final ObjectId newObjectId = rewriteRefTarget(tag.getObject(), ref);
        if (newObjectId == ZERO) {
            return ZERO;
        }
        log.debug("Rewrite tag target: {} -> {}", tag.getObject().name(), newObjectId.name());

        final TagBuilder builder = new TagBuilder();
        builder.setObjectId(newObjectId, Constants.OBJ_COMMIT);
        builder.setTag(rewriteTagName(tag.getTagName(), ref));
        builder.setTagger(rewriteTagger(tag.getTaggerIdent(), tag));
        builder.setMessage(rewriteTagMessage(tag.getFullMessage(), tag));

        final ObjectId newId = tryInsert((i) -> i.insert(builder));
        log.debug("Rewrite tag: {} -> {}", tagId.name(), newId.name());
        return newId;
    }

    /**
     * Rewrites the tagger identity of a tag.
     */
    protected PersonIdent rewriteTagger(final PersonIdent tagger, final RevTag tag) {
        return rewritePerson(tagger);
    }

    /**
     * Rewrites the message of a tag.
     */
    protected String rewriteTagMessage(final String message, final RevTag tag) {
        return rewriteMessage(message, tag.getId());
    }

    /**
     * Updates a ref name.
     */
    protected void updateRefName(final Ref ref) {
        final String oldName = ref.getName();
        final String newName = rewriteRefName(oldName, ref);
        if (!newName.equals(oldName)) {
            log.debug("Rename ref: {} -> {}", oldName, newName);
            Try.io(() -> repo.getRefDatabase().newRename(ref.getName(), newName).rename());
        }
    }

    /**
     * Rewrites a ref name.
     */
    protected String rewriteRefName(final String name, final Ref ref) {
        if (name.startsWith(Constants.R_HEADS)) {
            final String branchName = name.substring(Constants.R_HEADS.length());
            return Constants.R_HEADS + rewriteBranchName(branchName, ref);
        } else if (name.startsWith(Constants.R_TAGS)) {
            final String tagName = name.substring(Constants.R_TAGS.length());
            return Constants.R_TAGS + rewriteTagName(tagName, ref);
        } else {
            return name;
        }
    }

    /**
     * Rewrites a local branch name.
     */
    protected String rewriteBranchName(final String name, final Ref ref) {
        return name;
    }

    /**
     * Rewrites a tag name.
     */
    protected String rewriteTagName(final String name, final Ref ref) {
        return name;
    }

    @Override
    protected <R> R tryInsert(final ThrowableFunction<ObjectInserter, R> f) {
        if (inserter != null) {
            return Try.io(f).apply(inserter);
        } else {
            return super.tryInsert(f);
        }
    }
}
