package jp.ac.titech.c.se.stein.sample;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.util.sha1.SHA1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.CLI;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.EntrySet.Entry;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;

public class Anonymizer extends RepositoryRewriter {
    private static final Logger log = LoggerFactory.getLogger(Anonymizer.class);

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

    private String hash(final String data) {
        final SHA1 md = SHA1.newInstance();
        md.update(data.getBytes());
        return md.toObjectId().getName();
    }

    private String hash7(final String data) {
        return hash(data).substring(0, 7);
    }

    @Override
    public String rewriteMessage(final String message, final Context c) {
        final String name = c.getRev().name();
        return "orig:" + name + " " + hash7(message);
    }

    @Override
    public ObjectId rewriteBlob(final ObjectId blobId, final Context c) {
        return out.writeBlob(blobId.name().getBytes(), c);
    }

    @Override
    public String rewriteName(final String name, final Context c) {
        final Entry entry = c.getEntry();
        if (entry.isTree()) {
            return treeNameMap.convert(name);
        } else {
            return blobNameMap.convert(name);
        }
    }

    @Override
    public PersonIdent rewritePerson(final PersonIdent person, final Context c) {
        if (person == null) {
            return person;
        }
        final String name = personNameMap.convert(person.getName());
        final String address = hash7(person.getEmailAddress());
        return new PersonIdent(name, address, person.getWhen(), person.getTimeZone());
    }

    @Override
    public String rewriteBranchName(final String name, final Context c) {
        if (name.equals("master")) {
            return name;
        } else {
            return branchNameMap.convert(name);
        }
    }

    @Override
    public String rewriteTagName(final String name, final Context c) {
        return tagNameMap.convert(name);
    }

    public static void main(final String[] args) {
        new CLI(Anonymizer.class, args).run();
    }
}
