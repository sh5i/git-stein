package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.entry.TreeEntry;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Delegate;
import org.eclipse.jgit.lib.PersonIdent;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A commit-level translator that can rewrite commit metadata and/or entry content.
 *
 * <p>Operates at the commit level: message, author, committer, and tree entries.
 * Wrapped in a {@link Single} to run inside a {@link RepositoryRewriter}.</p>
 */
public interface CommitTranslator extends RewriterCommand {
    default void setUp(Context c) {}
    default String rewriteCommitMessage(String message, Context c) { return message; }
    default PersonIdent rewriteAuthor(PersonIdent author, Context c) { return author; }
    default PersonIdent rewriteCommitter(PersonIdent committer, Context c) { return committer; }
    default AnyHotEntry rewriteBlobEntry(BlobEntry entry, Context c) { return entry; }

    /**
     * Lifts a {@link BlobTranslator} into a {@link CommitTranslator} that only rewrites blob entries.
     */
    static CommitTranslator fromBlob(BlobTranslator translator) {
        return new CommitTranslator() {
            @Override
            public void setUp(Context c) {
                translator.setUp(c);
            }

            @Override
            public AnyHotEntry rewriteBlobEntry(BlobEntry entry, Context c) {
                return translator.rewriteBlobEntry(entry, c);
            }

            @Override
            public String toString() {
                return translator.toString();
            }
        };
    }

    static CommitTranslator composite(CommitTranslator... translators) {
        return new Composite(translators);
    }

    static CommitTranslator composite(List<CommitTranslator> translators) {
        return new Composite(translators);
    }

    @Override
    default RepositoryRewriter toRewriter() {
        return new Single(this);
    }

    @ToString
    class Single extends RepositoryRewriter {
        @Getter
        @Delegate
        private final CommitTranslator translator;

        public Single(CommitTranslator translator) {
            this.translator = translator;
        }

        @Override
        public void setUp(Context c) {
            translator.setUp(c);
        }
    }

    @ToString
    class Composite implements CommitTranslator {
        private final CommitTranslator[] translators;

        public Composite(CommitTranslator... translators) {
            this.translators = translators;
        }

        public Composite(List<CommitTranslator> translators) {
            this(translators.toArray(new CommitTranslator[0]));
        }

        @Override
        public void setUp(Context c) {
            for (CommitTranslator translator : translators) {
                translator.setUp(c);
            }
        }

        @Override
        public String rewriteCommitMessage(String message, Context c) {
            for (CommitTranslator translator : translators) {
                message = translator.rewriteCommitMessage(message, c);
            }
            return message;
        }

        @Override
        public PersonIdent rewriteAuthor(PersonIdent author, Context c) {
            for (CommitTranslator translator : translators) {
                author = translator.rewriteAuthor(author, c);
            }
            return author;
        }

        @Override
        public PersonIdent rewriteCommitter(PersonIdent committer, Context c) {
            for (CommitTranslator translator : translators) {
                committer = translator.rewriteCommitter(committer, c);
            }
            return committer;
        }

        // Same logic as BlobTranslator.Composite.apply
        @Override
        public AnyHotEntry rewriteBlobEntry(BlobEntry entry, Context c) {
            return apply(entry, List.of(translators), c);
        }

        private AnyHotEntry apply(AnyHotEntry input, List<CommitTranslator> rest, Context c) {
            if (input instanceof BlobEntry blob) {
                final CommitTranslator head = rest.get(0);
                final List<CommitTranslator> tail = rest.subList(1, rest.size());
                final AnyHotEntry result = head.rewriteBlobEntry(blob, c);
                return tail.isEmpty() ? result : apply(result, tail, c);
            }
            if (input instanceof TreeEntry tree) {
                final List<HotEntry> newChildren = tree.getHotEntries().stream()
                        .flatMap(e -> apply(e, rest, c).stream())
                        .collect(Collectors.toList());
                return tree.update(newChildren);
            }
            return AnyHotEntry.set(input.stream()
                    .flatMap(e -> apply(e, rest, c).stream())
                    .collect(Collectors.toList()));
        }
    }
}
