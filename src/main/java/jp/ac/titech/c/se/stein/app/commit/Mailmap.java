package jp.ac.titech.c.se.stein.app.commit;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.rewriter.CommitTranslator;
import lombok.AllArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a {@code .mailmap} file across the whole history, canonicalizing author and committer identities.
 * Supports all four <a href="https://git-scm.com/docs/gitmailmap">gitmailmap</a> line forms.
 */
@Slf4j
@ToString
@Command(name = "@mailmap", description = "Canonicalize author/committer identities via a .mailmap")
public class Mailmap implements CommitTranslator {
    @Option(names = "--mailmap", paramLabel = "<file>", description = "the .mailmap file (default: .mailmap at HEAD)")
    protected File mailmapFile;

    private final ListMultimap<String, Replacement> byEmail = ArrayListMultimap.create();

    @AllArgsConstructor
    private static class Replacement {
        final String matchName;
        final String canonicalName;
        final String canonicalEmail;
    }

    @Override
    public void setUp(final Context c) {
        final String content = readMailmap(c);
        if (content == null) {
            log.warn("No .mailmap found (neither --mailmap nor HEAD:.mailmap); no identities rewritten");
            return;
        }
        content.lines().forEach(this::parse);
    }

    private String readMailmap(final Context c) {
        if (mailmapFile != null) {
            try {
                return Files.readString(mailmapFile.toPath());
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        // git reads the repository's .mailmap by default (mailmap.blob = HEAD:.mailmap for a bare repo).
        final RepositoryAccess source = c.getRewriter().getSource();
        final ObjectId blob = source.resolve("HEAD:.mailmap");
        return blob != null ? new String(source.readBlob(blob), StandardCharsets.UTF_8) : null;
    }

    @Override
    public PersonIdent rewriteAuthor(final PersonIdent author, final Context c) {
        return canonicalize(author);
    }

    @Override
    public PersonIdent rewriteCommitter(final PersonIdent committer, final Context c) {
        return canonicalize(committer);
    }

    private PersonIdent canonicalize(final PersonIdent ident) {
        final List<Replacement> candidates = byEmail.get(ident.getEmailAddress().toLowerCase(Locale.ROOT));
        // Prefer an entry whose commit-side name matches; otherwise fall back to a name-agnostic one.
        Replacement hit = candidates.stream()
                .filter(r -> ident.getName().equals(r.matchName)).findFirst().orElse(null);
        if (hit == null) {
            hit = candidates.stream().filter(r -> r.matchName == null).findFirst().orElse(null);
        }
        if (hit == null) {
            return ident;
        }
        final String name = hit.canonicalName != null ? hit.canonicalName : ident.getName();
        return new PersonIdent(name, hit.canonicalEmail, ident.getWhenAsInstant(), ident.getZoneId());
    }

    /**
     * One mailmap line: an optional name (1) and email (2),
     * optionally followed by a second (commit-side) name (3) and email (4).
     */
    private static final Pattern MAILMAP_LINE = Pattern.compile("([^<]*)<([^>]+)>(?:([^<]*)<([^>]+)>)?");

    private void parse(final String rawLine) {
        final int hash = rawLine.indexOf('#');
        final String line = (hash >= 0 ? rawLine.substring(0, hash) : rawLine).trim();
        if (line.isEmpty()) {
            return;
        }
        final Matcher m = MAILMAP_LINE.matcher(line);
        if (!m.matches()) {
            log.warn("Ignore malformed mailmap line: {}", rawLine);
            return;
        }
        final String canonicalName = emptyToNull(m.group(1).trim());
        final String canonicalEmail = m.group(2).trim();
        final String matchEmail = m.group(4);
        if (matchEmail == null) {
            // "Proper Name <commit@email>": match commits with that email, replace the name
            add(canonicalName, canonicalEmail, null, canonicalEmail);
        } else {
            // "[Proper Name] <proper@email> [Commit Name] <commit@email>": match commit@email
            final String matchName = emptyToNull(m.group(3).trim());
            add(canonicalName, canonicalEmail, matchName, matchEmail.trim());
        }
    }

    private void add(final String canonicalName, final String canonicalEmail, final String matchName, final String matchEmail) {
        byEmail.put(matchEmail.toLowerCase(Locale.ROOT), new Replacement(matchName, canonicalName, canonicalEmail));
    }

    private static String emptyToNull(final String s) {
        return s.isEmpty() ? null : s;
    }
}
