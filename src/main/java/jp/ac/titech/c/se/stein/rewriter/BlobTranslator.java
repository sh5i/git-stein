package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.core.*;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.entry.TreeEntry;
import lombok.Getter;
import lombok.ToString;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public interface BlobTranslator extends RewriterCommand {
    default void setUp(final Context c) {}

    AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c);

    /**
     * Creates a {@link BlobTranslator} from a String-to-String function.
     */
    static BlobTranslator of(Function<String, String> f) {
        return (entry, c) -> entry.update(f.apply(new String(entry.getBlob(), StandardCharsets.UTF_8)));
    }

    default RepositoryRewriter toRewriter() {
        return new Single(this);
    }

    @ToString
    class Single extends RepositoryRewriter {
        @Getter
        private final BlobTranslator translator;

        public Single(BlobTranslator translator) {
            this.translator = translator;
        }

        @Override
        public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
            return translator.rewriteBlobEntry(entry, c);
        }
    }

    @ToString
    class Composite extends RepositoryRewriter {
        BlobTranslator[] translators;

        public Composite(BlobTranslator... translators) {
            this.translators = translators;
        }

        public Composite(List<BlobTranslator> translators) {
            this(translators.toArray(new BlobTranslator[0]));
        }

        @Override
        public void setUp(final Context c) {
            for (BlobTranslator translator : translators) {
                translator.setUp(c);
            }
        }

        @Override
        public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
            return apply(entry, c, 0);
        }

        private AnyHotEntry apply(AnyHotEntry input, Context c, int from) {
            if (from >= translators.length) {
                return input;
            }
            if (input instanceof TreeEntry.NewTree) {
                final TreeEntry.NewTree tree = (TreeEntry.NewTree) input;
                final List<HotEntry> newChildren = new ArrayList<>();
                for (HotEntry child : tree.getHotEntries()) {
                    collect(apply(child, c, from), newChildren);
                }
                return new TreeEntry.NewTree(tree.getName(), newChildren);
            }
            if (input.size() != 1) {
                final List<HotEntry> results = new ArrayList<>();
                input.stream().forEach(e ->
                        collect(apply(translators[from].rewriteBlobEntry((BlobEntry) e, c), c, from + 1), results));
                return AnyHotEntry.set(results);
            }
            return apply(translators[from].rewriteBlobEntry((BlobEntry) input.stream().findFirst().get(), c), c, from + 1);
        }

        private static void collect(AnyHotEntry result, List<HotEntry> out) {
            if (result instanceof HotEntry) {
                out.add((HotEntry) result);
            } else {
                result.stream().forEach(out::add);
            }
        }
    }
}
