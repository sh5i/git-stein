package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.core.*;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.entry.TreeEntry;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.stream.Collectors;
import java.util.function.Function;

public interface BlobTranslator extends RewriterCommand {
    default void setUp(final Context c) {}

    AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c);

    /**
     * Creates a {@link BlobTranslator} from a String-to-String function.
     */
    static BlobTranslator of(Function<String, String> f) {
        return (entry, c) -> entry.update(f.apply(entry.getContent()));
    }

    static BlobTranslator composite(BlobTranslator... translators) {
        return new Composite(translators);
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
            return apply(entry, List.of(translators), c);
        }

        private AnyHotEntry apply(AnyHotEntry input, List<BlobTranslator> rest, Context c) {
            if (input instanceof BlobEntry blob) {
                final BlobTranslator head = rest.get(0);
                final List<BlobTranslator> tail = rest.subList(1, rest.size());
                final AnyHotEntry result = head.rewriteBlobEntry(blob, c);
                return tail.isEmpty() ? result : apply(result, tail, c);
            }
            if (input instanceof TreeEntry tree) {
                final List<HotEntry> newChildren = tree.getHotEntries().stream()
                        .flatMap(e -> apply(e, rest, c).stream())
                        .collect(Collectors.toList());
                return tree.update(newChildren);
            }
            // Set/Empty: apply to each element
            return AnyHotEntry.set(input.stream()
                    .flatMap(e -> apply(e, rest, c).stream())
                    .collect(Collectors.toList()));
        }
    }
}
