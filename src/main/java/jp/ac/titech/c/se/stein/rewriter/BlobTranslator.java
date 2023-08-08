package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.core.*;
import lombok.Getter;
import lombok.ToString;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface BlobTranslator extends RepositoryRewriter.Factory {
    default void setUp(final Context c) {}

    HotEntry rewriteBlobEntry(final HotEntry.Single entry, final Context c);

    default RepositoryRewriter create() {
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
        public HotEntry rewriteBlobEntry(final HotEntry.Single entry, final Context c) {
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
        public HotEntry rewriteBlobEntry(final HotEntry.Single entry, final Context c) {
            Stream<HotEntry.Single> stream = Stream.of(entry);
            for (BlobTranslator translator : translators) {
                stream = stream.flatMap(e -> translator.rewriteBlobEntry(e, c).stream());
            }
            return HotEntry.of(stream.collect(Collectors.toList()));
        }
    }
}
