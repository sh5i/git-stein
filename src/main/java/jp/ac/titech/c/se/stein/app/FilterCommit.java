package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.rewriter.CommitDropper;
import lombok.ToString;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * Keeps commits whose metadata matches every given predicate and drops the rest, filtering the
 * history down to a subset; dropped commits are spliced out so survivors reconnect across the gap.
 * With no predicate every commit is kept. Predicates are combined with AND; {@code --author} and
 * {@code --grep} are regular expressions and {@code --since}/{@code --until} are ISO-8601 instants
 * (e.g. {@code 2024-01-01T00:00:00Z}).
 */
@ToString
@Command(name = "@filter-commit", description = "Keep commits matching metadata predicates, drop the rest")
public class FilterCommit extends CommitDropper {
    @Option(names = "--author", paramLabel = "<regex>", description = "keep commits whose author matches")
    protected Pattern author;

    @Option(names = "--grep", paramLabel = "<regex>", description = "keep commits whose message matches")
    protected Pattern grep;

    @Option(names = "--since", paramLabel = "<instant>", description = "keep commits committed at or after this ISO instant")
    protected Instant since;

    @Option(names = "--until", paramLabel = "<instant>", description = "keep commits committed at or before this ISO instant")
    protected Instant until;

    @Override
    protected boolean shouldDrop(final RevCommit commit, final ObjectId treeId, final ObjectId[] parentIds, final Context c) {
        return !keeps(commit);
    }

    private boolean keeps(final RevCommit commit) {
        if (author != null && !author.matcher(format(commit.getAuthorIdent())).find()) {
            return false;
        }
        if (grep != null && !grep.matcher(commit.getFullMessage()).find()) {
            return false;
        }
        final Instant when = commit.getCommitterIdent().getWhenAsInstant();
        if (since != null && when.isBefore(since)) {
            return false;
        }
        return until == null || !when.isAfter(until);
    }

    private static String format(final PersonIdent id) {
        return id.getName() + " <" + id.getEmailAddress() + ">";
    }
}
