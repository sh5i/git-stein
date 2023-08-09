package jp.ac.titech.c.se.stein.core;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import jp.ac.titech.c.se.stein.entry.ColdEntry;
import jp.ac.titech.c.se.stein.entry.Entry;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;

public class Editor {
    protected final RepositoryAccess ra;

    protected final PersonIdent author;

    protected final PersonIdent committer;

    protected ObjectId currentCommit;

    protected TreeNode currentTree;

    protected Context c;

    public Editor(final RepositoryAccess ra, final PersonIdent author, final PersonIdent committer) {
        this.ra = ra;
        this.author = author;
        this.committer = committer;
    }

    public Editor(final RepositoryAccess ra) {
        this(ra, null, null);
    }

    public Editor open(final RevCommit commit) {
        this.currentCommit = commit.getId();
        open(commit.getTree().getId());
        return this;
    }

    public void setContext(final Context c) {
        this.c = c;
    }

    public TreeNode open(final ObjectId treeId) {
        return currentTree = new TreeNode(treeId);
    }

    public void commit(final String message, final Consumer<TreeNode> f) {
        if (currentCommit == null) {
            throw new IllegalStateException("Opening a commit is necessary before editing it");
        }
        f.accept(currentTree);
        final ObjectId newTreeId = currentTree.getId();
        final ObjectId[] parents = new ObjectId[] { currentCommit };
        currentCommit = ra.writeCommit(parents, newTreeId, author, committer, message, c);
    }

    public ObjectId editTree(final ObjectId treeId, final Consumer<TreeNode> f) {
        final TreeNode tree = new TreeNode(treeId);
        f.accept(tree);
        return tree.getId();
    }

    public abstract static class Node {
        protected ObjectId id;

        public Node(final ObjectId id) {
            this.id = id;
        }

        public abstract ObjectId getId();
    }

    public class TreeNode extends Node {
        protected final Map<String, Entry> entries = new HashMap<>();

        public TreeNode(final ObjectId id) {
            super(id);
            if (id != null) {
                for (final Entry e : ra.readTree(id, null)) {
                    entries.put(e.name, e);
                }
            }
        }

        public void update(final String path, final Function<String, String> f) {
            final BlobNode blob = getBlob(path, false);
            blob.set(f.apply(blob.get()));
        }

        public void set(final String path, final String body) {
            getBlob(path).set(body);
        }

        public BlobNode getBlob(final String path) {
            final List<String> segments = Arrays.asList(path.split("/"));
            return getBlob(segments, false);
        }

        public BlobNode getBlob(final List<String> segments, final boolean create) {
            final String name = segments.get(0);
            final int size = segments.size();
            if (size > 1) {
                final TreeNode tree = getTree(name, create);
                return tree.getBlob(segments.subList(1, size), create);
            } else {
                return getBlob(name, create);
            }
        }

        protected BlobNode getBlob(final String name, final boolean create) {
            Entry e = entries.get(name);
            if (e == null) {
                if (!create) {
                    throw new IllegalStateException("No entry");
                }
                e = ColdEntry.of(FileMode.REGULAR_FILE.getBits(), name, null);
                entries.put(name, e);
            } else {
                if (e.isTree()) {
                    throw new IllegalStateException("Blob expected, but a tree found");
                }
                if (e.data != null) {
                    return (BlobNode) e.data;
                }
            }
            final BlobNode result = new BlobNode(e.id);
            e.data = result;
            return result;
        }

        protected TreeNode getTree(final String name, final boolean create) {
            Entry e = entries.get(name);
            if (e == null) {
                if (!create) {
                    throw new IllegalStateException("No entry");
                }
                e = ColdEntry.of(FileMode.TREE.getBits(), name, null);
                entries.put(name, e);
            } else {
                if (!e.isTree()) {
                    throw new IllegalStateException("Tree expected, but a blob found");
                }
                if (e.data != null) {
                    return (TreeNode) e.data;
                }
            }
            final TreeNode result = new TreeNode(e.id);
            e.data = result;
            return result;
        }

        @Override
        public ObjectId getId() {
            if (id == null) {
                id = ra.writeTree(entries.values(), c);
            }
            return id;
        }
    }

    public class BlobNode extends Node {
        protected byte[] body;

        public BlobNode(final ObjectId id) {
            super(id);
        }

        public byte[] getRaw() {
            if (body == null) {
                body = ra.readBlob(id);
            }
            return body;
        }

        public String get() {
            return new String(getRaw(), StandardCharsets.UTF_8);
        }

        public void setRaw(final byte[] body) {
            this.body = body;
            this.id = null;
        }

        public void set(final String s) {
            setRaw(s.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public ObjectId getId() {
            if (id == null) {
                id = ra.writeBlob(body, c);
            }
            return id;
        }
    }
}
