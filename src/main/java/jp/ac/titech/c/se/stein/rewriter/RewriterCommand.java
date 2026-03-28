package jp.ac.titech.c.se.stein.rewriter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public interface RewriterCommand {
    Logger log = LoggerFactory.getLogger(RewriterCommand.class);

    RepositoryRewriter toRewriter();

    /**
     * Optimizes a list of commands by composing consecutive translators.
     * Phase 1: compose consecutive BlobTranslators.
     * Phase 2: compose consecutive CommitTranslators and BlobTranslators (lifting BlobTranslators).
     */
    static List<RewriterCommand> optimize(final List<RewriterCommand> commands) {
        return composeCommitTranslators(composeBlobTranslators(commands));
    }

    private static List<RewriterCommand> composeBlobTranslators(final List<RewriterCommand> commands) {
        final List<RewriterCommand> result = new ArrayList<>();
        final List<BlobTranslator> pending = new ArrayList<>();
        for (final RewriterCommand cmd : commands) {
            if (cmd instanceof BlobTranslator t) {
                pending.add(t);
            } else {
                flushBlobs(pending, result);
                result.add(cmd);
            }
        }
        flushBlobs(pending, result);
        return result;
    }

    private static void flushBlobs(final List<BlobTranslator> pending, final List<RewriterCommand> result) {
        if (pending.isEmpty()) {
            return;
        }
        if (pending.size() >= 2) {
            log.info("Compose {} blob translators: {}", pending.size(), pending);
            result.add(BlobTranslator.composite(pending));
        } else {
            result.add(pending.get(0));
        }
        pending.clear();
    }

    private static List<RewriterCommand> composeCommitTranslators(final List<RewriterCommand> commands) {
        final List<RewriterCommand> result = new ArrayList<>();
        final List<RewriterCommand> pending = new ArrayList<>();
        for (final RewriterCommand cmd : commands) {
            if (cmd instanceof CommitTranslator || cmd instanceof BlobTranslator) {
                pending.add(cmd);
            } else {
                flushCommits(pending, result);
                result.add(cmd);
            }
        }
        flushCommits(pending, result);
        return result;
    }

    private static void flushCommits(final List<RewriterCommand> pending, final List<RewriterCommand> result) {
        if (pending.isEmpty()) {
            return;
        }
        if (pending.size() >= 2) {
            final List<CommitTranslator> lifted = pending.stream()
                    .map(c -> c instanceof BlobTranslator t ? CommitTranslator.fromBlob(t) : (CommitTranslator) c)
                    .collect(Collectors.toList());
            log.info("Compose {} commit translators: {}", lifted.size(), lifted);
            result.add(CommitTranslator.composite(lifted));
        } else {
            result.addAll(pending);
        }
        pending.clear();
    }
}
