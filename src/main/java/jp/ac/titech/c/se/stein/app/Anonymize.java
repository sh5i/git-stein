package jp.ac.titech.c.se.stein.app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.util.HashUtils;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.PersonIdent;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Slf4j
@ToString
@Command(name = "@anonymize", description = "Anonymize filenames and contents")
public class Anonymize extends RepositoryRewriter {
    @Option(names = "--tree", description = "anonymize tree name")
    protected boolean isTreeNameEnabled;

    @Option(names = "--blob", description = "anonymize blob name")
    protected boolean isBlobNameEnabled;

    @Option(names = "--content", description = "anonymize blob content")
    protected boolean isBlobContentEnabled;

    @Option(names = "--message", description = "anonymize commit/tag message")
    protected boolean isMessageEnabled;

    @Option(names = "--branch", description = "anonymize branch name")
    protected boolean isBranchNameEnabled;

    @Option(names = "--tag", description = "anonymize tag name")
    protected boolean isTagNameEnabled;

    @Option(names = "--author", description = "anonymize author name")
    protected boolean isAuthorNameEnabled;

    @Option(names = "--email", description = "anonymize author email")
    protected boolean isAuthorEmailEnabled;

    @SuppressWarnings("unused")
    @Option(names = "--all", description = "anonymize all")
    protected void setAllEnabled(boolean isEnabled) {
        isTreeNameEnabled = isEnabled;
        isBlobNameEnabled = isEnabled;
        isBlobContentEnabled = isEnabled;
        isMessageEnabled = isEnabled;
        isBranchNameEnabled = isEnabled;
        isTagNameEnabled = isEnabled;
        isAuthorNameEnabled = isEnabled;
        isAuthorEmailEnabled = isEnabled;
    }

    public static class NameMap {
        private final Map<String, String> cache = new ConcurrentHashMap<>();

        private int count = 0;

        private final String type;

        private final String prefix;

        public NameMap(final String type, final String prefix) {
            this.type = type;
            this.prefix = prefix;
        }

        private synchronized int increment() {
            return ++count;
        }

        public String convert(final String name) {
            return cache.computeIfAbsent(name, n -> {
                final String result = prefix + increment();
                log.debug("New {}: {} -> {}", type, name, result);
                return prefix + increment();
            });
        }
    }

    private final NameMap treeNameMap = new NameMap("directory", "t");

    private final NameMap blobNameMap = new NameMap("file", "f");

    private final NameMap branchNameMap = new NameMap("branch", "b");

    private final NameMap tagNameMap = new NameMap("tag", "t");

    private final NameMap personNameMap = new NameMap("person", "p");

    @Override
    public String rewriteMessage(final String message, final Context c) {
        return isMessageEnabled ? HashUtils.digest(message, 7) : message;
    }

    @Override
    public AnyHotEntry rewriteBlobEntry(HotEntry entry, final Context c) {
        if (isBlobContentEnabled) {
            entry = entry.update(entry.getId().name());
        }
        if (isBlobNameEnabled) {
            entry = entry.rename(blobNameMap.convert(entry.getName()));
        }
        return entry;
    }

    @Override
    public String rewriteName(final String name, final Context c) {
        final Entry entry = c.getEntry();
        if (entry.isTree()) {
            return isTreeNameEnabled ? treeNameMap.convert(name) : name;
        }
        return name;
    }

    @Override
    public PersonIdent rewritePerson(final PersonIdent person, final Context c) {
        if (person == null) {
            return null;
        }
        final String name = isAuthorNameEnabled ? personNameMap.convert(person.getName()) : person.getName();
        final String address = isAuthorEmailEnabled ? HashUtils.digest(person.getEmailAddress(), 7) : person.getEmailAddress();
        return new PersonIdent(name, address, person.getWhen(), person.getTimeZone());
    }

    @Override
    public String rewriteBranchName(final String name, final Context c) {
        if (name.equals("master") || name.equals("main")) {
            return name;
        } else {
            return isBranchNameEnabled ? branchNameMap.convert(name) : name;
        }
    }

    @Override
    public String rewriteTagName(final String name, final Context c) {
        return isTagNameEnabled ? tagNameMap.convert(name) : name;
    }
}
