package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;

@Slf4j
@ToString
@Command(name = "@note-commit", description = "Note original commit id on each commit message")
public class NoteCommit extends RepositoryRewriter {
    @Option(names = "--length", paramLabel = "<num>", description = "length of SHA1 hash (default: ${DEFAULT-VALUE})")
    protected int length = 20;

    @Override
    public String rewriteCommitMessage(final String message, final Context c) {
        final ObjectId current = c.getRev().getId();
        final byte[] note = source.readNote(source.getDefaultNotes(), current);
        if (note != null && note.length == 20) {
            // use the commit note for the original commit id
            return new String(note, StandardCharsets.UTF_8).substring(0, length) + " " + message;
        }
        // no note or no valid note: use the current commit id
        return current.name().substring(0, length) + " " + message;
    }
}
