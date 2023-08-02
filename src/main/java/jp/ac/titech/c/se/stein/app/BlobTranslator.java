package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.*;
import lombok.Getter;
import lombok.ToString;

import java.util.stream.Collectors;
import java.util.stream.Stream;

public interface BlobTranslator extends RepositoryRewriter.Factory {

    HotEntry rewriteBlobEntry(final HotEntry.SingleHotEntry entry, final Context c);

    default RepositoryRewriter create() {
        return new Rewriter(this);
    }

    @ToString
    class Single extends RepositoryRewriter {
        @Getter
        private final BlobTranslator translator;

        public Rewriter(BlobTranslator translator) {
            this.translator = translator;
        }

        @Override
        public HotEntry rewriteBlobEntry(final HotEntry.SingleHotEntry entry, final Context c) {
            return translator.rewriteBlobEntry(entry, c);
        }
    }

    @ToString
    class Composite extends RepositoryRewriter {
        BlobTranslator[] translators;

        public Composite(BlobTranslator... translators) {
            this.translators = translators;
        }

        public Composite(BlobTranslator.Rewriter... rewriters) {
            this(Stream.of(rewriters).map(Rewriter::getTranslator).toArray(BlobTranslator[]::new));
        }

        @Override
        public HotEntry rewriteBlobEntry(final HotEntry.SingleHotEntry entry, final Context c) {
            Stream<HotEntry.SingleHotEntry> stream = Stream.of(entry);
            for (BlobTranslator translator : translators) {
                stream = stream.flatMap(e -> translator.rewriteBlobEntry(e, c).stream());
            }
            return HotEntry.of(stream.collect(Collectors.toList()));
        }
    }
}
