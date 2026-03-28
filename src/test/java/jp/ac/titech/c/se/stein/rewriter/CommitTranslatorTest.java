package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.app.blob.TokenizeViaJDT;
import jp.ac.titech.c.se.stein.app.commit.NoteCommit;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class CommitTranslatorTest {
    static final Context CTX = Context.init();

    static RepositoryAccess source;

    @BeforeAll
    static void setUp() throws IOException {
        source = TestRepo.createSample();
    }

    @AfterAll
    static void tearDown() {
        source.close();
    }

    @Test
    public void testMessageRewrite() {
        final CommitTranslator t = new CommitTranslator() {
            @Override
            public String rewriteCommitMessage(String message, Context c) {
                return "[tag] " + message;
            }
        };
        assertEquals("[tag] hello", t.rewriteCommitMessage("hello", CTX));
    }

    @Test
    public void testCompositeMessage() {
        final CommitTranslator a = new CommitTranslator() {
            @Override
            public String rewriteCommitMessage(String message, Context c) {
                return "A:" + message;
            }
        };
        final CommitTranslator b = new CommitTranslator() {
            @Override
            public String rewriteCommitMessage(String message, Context c) {
                return "B:" + message;
            }
        };
        final CommitTranslator composite = CommitTranslator.composite(a, b);
        assertEquals("B:A:hello", composite.rewriteCommitMessage("hello", CTX));
    }

    @Test
    public void testCompositeAuthor() {
        final CommitTranslator renamer = new CommitTranslator() {
            @Override
            public PersonIdent rewriteAuthor(PersonIdent author, Context c) {
                return new PersonIdent("Replaced", author.getEmailAddress());
            }
        };
        final CommitTranslator tagger = new CommitTranslator() {
            @Override
            public PersonIdent rewriteAuthor(PersonIdent author, Context c) {
                return new PersonIdent(author.getName() + " [bot]", author.getEmailAddress());
            }
        };
        final CommitTranslator composite = CommitTranslator.composite(renamer, tagger);
        final PersonIdent original = new PersonIdent("Alice", "alice@example.com");
        final PersonIdent result = composite.rewriteAuthor(original, CTX);
        assertEquals("Replaced [bot]", result.getName());
        assertEquals("alice@example.com", result.getEmailAddress());
    }

    @Test
    public void testFromBlob() {
        final BlobTranslator upper = BlobTranslator.of(String::toUpperCase);
        final CommitTranslator lifted = CommitTranslator.fromBlob(upper);
        final AnyHotEntry result = lifted.rewriteBlobEntry(HotEntry.ofBlob("f.txt", "hello"), CTX);
        assertEquals("HELLO", result.asBlob().getContent());
        // message is identity
        assertEquals("msg", lifted.rewriteCommitMessage("msg", CTX));
    }

    @Test
    public void testCompositeBlobRewrite() {
        final CommitTranslator composite = CommitTranslator.composite(
                CommitTranslator.fromBlob(BlobTranslator.of(s -> s + "!")),
                CommitTranslator.fromBlob(BlobTranslator.of(String::toUpperCase)));
        final AnyHotEntry result = composite.rewriteBlobEntry(HotEntry.ofBlob("f.txt", "hello"), CTX);
        assertEquals("HELLO!", result.asBlob().getContent());
    }

    @Test
    public void testCompositeMessageAndBlob() {
        final CommitTranslator msgRewriter = new CommitTranslator() {
            @Override
            public String rewriteCommitMessage(String message, Context c) {
                return "[processed] " + message;
            }
        };
        final CommitTranslator composite = CommitTranslator.composite(
                CommitTranslator.fromBlob(BlobTranslator.of(String::toUpperCase)),
                msgRewriter);
        assertEquals("[processed] hello", composite.rewriteCommitMessage("hello", CTX));
        assertEquals("HELLO", composite.rewriteBlobEntry(HotEntry.ofBlob("f.txt", "hello"), CTX).asBlob().getContent());
    }

    @Test
    public void testCompositeWithBlobOnRepo() throws IOException {
        // Tokenize (BlobTranslator) + NoteCommit (CommitTranslator) composed vs sequential
        final List<RevCommit> sourceCommits = source.collectCommits("refs/heads/main");

        final CommitTranslator composite = CommitTranslator.composite(
                CommitTranslator.fromBlob(new TokenizeViaJDT()),
                new NoteCommit());

        try (RepositoryAccess compositeResult = TestRepo.rewrite(source, composite);
             RepositoryAccess step1 = TestRepo.rewrite(source, new TokenizeViaJDT());
             RepositoryAccess sequentialResult = TestRepo.rewrite(step1, new NoteCommit())) {

            final List<RevCommit> compositeCommits = compositeResult.collectCommits("refs/heads/main");
            final List<RevCommit> sequentialCommits = sequentialResult.collectCommits("refs/heads/main");
            assertEquals(compositeCommits.size(), sequentialCommits.size());

            // Both should have original commit IDs in the message
            for (int i = 0; i < compositeCommits.size(); i++) {
                final String compositeMsg = compositeCommits.get(i).getFullMessage();
                final String expectedPrefix = sourceCommits.get(i).getId().name();
                assertTrue(compositeMsg.startsWith(expectedPrefix + " "),
                        "Expected original id " + expectedPrefix + " in: " + compositeMsg);
            }

            // Tree content should match
            final RevCommit compositeHead = compositeResult.getHead("refs/heads/main");
            final RevCommit sequentialHead = sequentialResult.getHead("refs/heads/main");
            final List<Entry> compositeFiles = compositeResult.flattenTree(compositeHead.getTree().getId());
            final List<Entry> sequentialFiles = sequentialResult.flattenTree(sequentialHead.getTree().getId());

            assertEquals(
                    compositeFiles.stream().map(Entry::getName).sorted().collect(Collectors.toList()),
                    sequentialFiles.stream().map(Entry::getName).sorted().collect(Collectors.toList()));

            for (Entry ce : compositeFiles) {
                final Entry se = sequentialFiles.stream()
                        .filter(e -> e.getName().equals(ce.getName()))
                        .findFirst().orElseThrow();
                assertEquals(ce.getId(), se.getId(), "blob mismatch for " + ce.getName());
            }
        }
    }
}
