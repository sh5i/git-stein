package jp.ac.titech.c.se.stein.app.commit;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.rewriter.CommitTranslator;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;

/**
 * Prepends the original commit ID to each commit message.
 * If the source has notes (chained transformation), the original ID is read from the note.
 * Otherwise, the current commit ID itself is used as the original.
 */
@Slf4j
@ToString
@Command(name = "@note-commit", description = "Note original commit id on each commit message")
public class NoteCommit implements CommitTranslator {
    @Option(names = "--length", paramLabel = "<num>", description = "length of SHA1 hash (default: ${DEFAULT-VALUE})")
    protected int length = 40;

    @Override
    public String rewriteCommitMessage(final String message, final Context c) {
        final ObjectId originalId = resolveOriginalId(c);
        return originalId.name().substring(0, length) + " " + message;
    }

    private ObjectId resolveOriginalId(final Context c) {
        final ObjectId current = c.getRev().getId();
        final RepositoryAccess source = c.getRewriter().getSource();
        final byte[] note = source.readNote(source.getNotes(RepositoryRewriter.R_NOTES_ORIG), current);
        if (note != null && note.length == 40) {
            return ObjectId.fromString(new String(note, StandardCharsets.UTF_8));
        } else {
            return current;
            }
    }
}
