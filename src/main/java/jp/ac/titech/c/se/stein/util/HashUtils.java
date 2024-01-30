package jp.ac.titech.c.se.stein.util;

import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.jgit.TreeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.util.sha1.SHA1;

import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;

@Slf4j
public class HashUtils {
    public static String digest(final byte[] data, final int length) {
        final SHA1 sha1 = SHA1.newInstance();
        sha1.update(data);
        return ObjectId.fromRaw(sha1.digest()).abbreviate(length).name();
    }

    public static String digest(final byte[] data) {
        final SHA1 sha1 = SHA1.newInstance();
        sha1.update(data);
        return ObjectId.fromRaw(sha1.digest()).name();
    }

    public static String digest(final String data, final int length) {
        return digest(data.getBytes(StandardCharsets.UTF_8), length);
    }

    public static String digest(final String data) {
        return digest(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Computes blob id from blob.
     */
    public static ObjectId idFor(final byte[] blob) {
        try (ObjectInserter inserter = new ObjectInserter.Formatter()) {
            return inserter.idFor(Constants.OBJ_BLOB, blob);
        }
    }

    /**
     * Computes tree id from tree.
     */
    public static ObjectId idFor(final List<Entry> entries) {
        try (ObjectInserter inserter = new ObjectInserter.Formatter()) {
            final TreeFormatter f = new TreeFormatter();
            RepositoryAccess.resolveNameConflicts(entries).stream()
                    .sorted(Comparator.comparing(Entry::sortKey))
                    .forEach(e -> f.append(e.name, e.mode, e.id));
            return f.computeId(inserter);
        }
    }
}
