package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.core.*;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import lombok.Getter;
import lombok.ToString;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
            Stream<? extends HotEntry> stream = Stream.of(entry);
            for (BlobTranslator translator : translators) {
                // TODO: if e is not BlobEntry?
                stream = stream.flatMap(e -> translator.rewriteBlobEntry((BlobEntry) e, c).stream());
            }
            return AnyHotEntry.set(stream.collect(Collectors.toList()));
        }
    }
}
