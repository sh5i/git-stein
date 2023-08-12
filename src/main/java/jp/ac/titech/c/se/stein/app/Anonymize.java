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
    @Option(names = "--no-tree", negatable = true, description = "anonymize tree name")
    protected boolean isTreeNameEnabled = true;

    @Option(names = "--no-blob", negatable = true, description = "anonymize blob name")
    protected boolean isBlobNameEnabled = true;

    @Option(names = "--no-content", negatable = true, description = "anonymize blob content")
    protected boolean isBlobContentEnabled = true;

    @Option(names = "--no-message", negatable = true, description = "anonymize commit/tag message")
    protected boolean isMessageEnabled = true;

    @Option(names = "--no-branch", negatable = true, description = "anonymize branch name")
    protected boolean isBranchNameEnabled = true;

    @Option(names = "--no-tag", negatable = true, description = "anonymize tag name")
    protected boolean isTagNameEnabled = true;

    @Option(names = "--no-author", negatable = true, description = "anonymize author name")
    protected boolean isAuthorNameEnabled = true;

    @Option(names = "--no-email", negatable = true, description = "anonymize author email")
    protected boolean isAuthorEmailEnabled = true;


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
