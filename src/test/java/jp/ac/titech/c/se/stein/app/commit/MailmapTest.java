package jp.ac.titech.c.se.stein.app.commit;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RefEntry;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MailmapTest {
    private final Context c = Context.init();
    private Mailmap app;

    @BeforeEach
    void setUp() throws IOException {
        final Path mm = Files.createTempFile("mailmap", "");
        Files.writeString(mm, """
                Proper Name <proper@x> Old Name <old@x>
                <canon@y> <alias@y>
                # a comment line
                Single Name <single@z>
                """);
        app = new Mailmap();
        app.mailmapFile = mm.toFile();
        app.setUp(c);
    }

    private PersonIdent person(final String name, final String email) {
        return new PersonIdent(name, email, Instant.EPOCH, ZoneOffset.UTC);
    }

    @Test
    public void testMapsNameAndEmailByCommitNameAndEmail() {
        final PersonIdent r = app.rewriteAuthor(person("Old Name", "old@x"), c);
        assertEquals("Proper Name", r.getName());
        assertEquals("proper@x", r.getEmailAddress());
    }

    @Test
    public void testMapsEmailOnlyKeepingName() {
        final PersonIdent r = app.rewriteCommitter(person("Whoever", "alias@y"), c);
        assertEquals("Whoever", r.getName()); // no canonical name given, so the name is kept
        assertEquals("canon@y", r.getEmailAddress());
    }

    @Test
    public void testReplacesNameForBareEmailMatch() {
        final PersonIdent r = app.rewriteAuthor(person("typo name", "single@z"), c);
        assertEquals("Single Name", r.getName());
        assertEquals("single@z", r.getEmailAddress());
    }

    @Test
    public void testUnmatchedIdentityIsUnchanged() {
        final PersonIdent original = person("Nobody", "nobody@elsewhere");
        final PersonIdent r = app.rewriteAuthor(original, c);
        assertEquals("Nobody", r.getName());
        assertEquals("nobody@elsewhere", r.getEmailAddress());
    }

    @Test
    public void testNameSpecificEntryIgnoresADifferentNameWithTheSameEmail() {
        // "Proper Name <proper@x> Old Name <old@x>" requires the commit name "Old Name"; a different
        // name with old@x is left alone, since there is no email-only entry for old@x to fall back to.
        final PersonIdent r = app.rewriteAuthor(person("Someone Else", "old@x"), c);
        assertEquals("Someone Else", r.getName());
        assertEquals("old@x", r.getEmailAddress());
    }

    @Test
    public void testReadsMailmapFromHeadWhenNoFileGiven() throws IOException {
        // A repository carrying its own .mailmap is canonicalized with no --mailmap option.
        final RepositoryAccess src = TestRepo.create();
        try (final ObjectInserter ins = src.repo.newObjectInserter()) {
            final Context ctx = Context.init().with(Context.Key.inserter, ins);
            final PersonIdent old = person("Old Name", "old@x");
            final ObjectId blob = src.writeBlob("Proper Name <proper@x> Old Name <old@x>\n".getBytes(StandardCharsets.UTF_8), ctx);
            final ObjectId tree = src.writeTree(List.of(Entry.of(FileMode.REGULAR_FILE.getBits(), ".mailmap", blob)), ctx);
            final ObjectId commit = src.writeCommit(RepositoryAccess.NO_PARENTS, tree, old, old, "c", ctx);
            ins.flush();
            src.applyRefUpdate(RefEntry.of("refs/heads/main", commit));
            src.applyRefUpdate(RefEntry.of("HEAD", "refs/heads/main"));
        }
        final RepositoryAccess result = TestRepo.rewrite(src, new Mailmap());
        final PersonIdent author = result.collectCommits("refs/heads/main").get(0).getAuthorIdent();
        assertEquals("Proper Name", author.getName());
        assertEquals("proper@x", author.getEmailAddress());
        result.close();
        src.close();
    }
}
