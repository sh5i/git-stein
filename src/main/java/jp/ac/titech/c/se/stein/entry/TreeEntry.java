package jp.ac.titech.c.se.stein.entry;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import lombok.Getter;
import lombok.experimental.Delegate;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A Hot entry representing a tree (directory).
 *
 * @see SourceTree
 * @see NewTree
 */
public abstract class TreeEntry extends HotEntry {
    @Override
    public int getMode() {
        return FileMode.TREE.getBits();
    }

    /**
     * Returns a new {@link NewTree} with the given name, keeping the children unchanged.
     */
    public NewTree rename(String newName) {
        return new NewTree(newName, getHotEntries());
    }

    /**
     * Returns a new {@link NewTree} with the given children, keeping the name unchanged.
     */
    public NewTree update(List<HotEntry> newChildren) {
        return new NewTree(getName(), newChildren);
    }

    /**
     * Returns the children as cold entries.
     */
    public abstract List<Entry> getEntries();

    /**
     * Returns the children as hot entries.
     */
    public abstract List<HotEntry> getHotEntries();

    /**
     * A Hot tree entry backed by an existing tree in a repository.
     * The tree contents are lazily loaded on the first call to {@link #getEntries()}.
     */
    public static class SourceTree extends TreeEntry {
        @Delegate(types = SingleEntry.class)
        private final Entry entry;

        private final RepositoryAccess source;

        private final String directory;

        private List<Entry> entries;

        SourceTree(Entry entry, RepositoryAccess source, String directory) {
            this.entry = entry;
            this.source = source;
            this.directory = directory;
        }

        @Override
        public List<Entry> getEntries() {
            if (entries == null) {
                entries = source.readTree(entry.id, directory);
            }
            return entries;
        }

        @Override
        public List<HotEntry> getHotEntries() {
            return getEntries().stream().map(e -> {
                if (e.isTree()) {
                    return HotEntry.ofTree(e, source, directory != null ? directory + "/" + e.getName() : null);
                } else {
                    return HotEntry.of(e, source);
                }
            }).collect(Collectors.toList());
        }

        @Override
        public Entry fold(RepositoryAccess target, Context c) {
            return entry;
        }

        @Override
        public String toString() {
            return String.format("%s [source-tree:%o]", getPath(), getMode());
        }
    }

    /**
     * A new tree node holding child entries in memory.
     *
     * <p>BlobTranslators can return a NewTree to produce subdirectory structures.
     * On {@link #fold}, children are recursively folded and an empty tree
     * (all children produce zero IDs) collapses to a zero-ID entry.</p>
     */
    public static class NewTree extends TreeEntry {
        @Getter
        private final String name;
        @Getter
        private final List<HotEntry> hotEntries;

        public NewTree(String name, List<HotEntry> hotEntries) {
            this.name = name;
            this.hotEntries = hotEntries;
        }

        public NewTree(String name, HotEntry... hotEntries) {
            this(name, new ArrayList<>(Arrays.asList(hotEntries)));
        }

        @Override
        public List<Entry> getEntries() {
            throw new UnsupportedOperationException("NewTree has no object ID");
        }

        @Override
        public ObjectId getId() {
            throw new UnsupportedOperationException("NewTree has no object ID");
        }

        @Override
        public String getDirectory() {
            return null;
        }

        @Override
        public Entry fold(RepositoryAccess target, Context c) {
            final List<Entry> entries = new ArrayList<>();
            for (HotEntry child : hotEntries) {
                final Entry folded = child.fold(target, c);
                if (!folded.getId().equals(ObjectId.zeroId())) {
                    entries.add(folded);
                }
            }
            return Entry.of(FileMode.TREE.getBits(), name,
                    entries.isEmpty() ? ObjectId.zeroId() : target.writeTree(entries, c));
        }

        @Override
        public String toString() {
            return name + "/" + hotEntries;
        }
    }
}
