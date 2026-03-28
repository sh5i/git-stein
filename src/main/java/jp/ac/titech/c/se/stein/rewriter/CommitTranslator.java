package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Delegate;
import org.eclipse.jgit.lib.PersonIdent;

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
}
