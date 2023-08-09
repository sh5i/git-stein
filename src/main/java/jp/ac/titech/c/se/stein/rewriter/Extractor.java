package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.core.*;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.sha1.SHA1;
import picocli.CommandLine.Option;

import java.util.*;

@Slf4j
public abstract class Extractor implements BlobTranslator {
    @Option(names = "--no-original", negatable = true, description = "[ex]/include original files")
    protected boolean requiresOriginals = true;

    @Override
    public HotEntry rewriteBlobEntry(final HotEntry.Single entry, final Context c) {
        if (!accept(entry.getName().toLowerCase())) {
            return entry;
        }
        final HotEntry.Set result = HotEntry.set();
        if (requiresOriginals) {
            result.add(entry);
        }
        final SourceText text = SourceText.ofNormalized(entry.getBlob());
        final Collection<? extends HotEntry.Single> entries = generate(entry, text, c);
        if (!entries.isEmpty()) {
            for (final HotEntry.Single e : entries) {
                log.debug("Generate submodule: {} from {} {}", e.getName(), entry, c);
                result.add(e);
            }
            log.debug("Rewrite entry: {} -> {} entries {}", entry, result.size(), c);
        }
        return result;
    }

    protected abstract boolean accept(final String filename);

    protected abstract Collection<? extends HotEntry.Single> generate(final HotEntry.Single entry, final SourceText text, final Context c);

    public static String digest(final String name, final int length) {
        final SHA1 sha1 = SHA1.newInstance();
        sha1.update(name.getBytes());
        return ObjectId.fromRaw(sha1.digest()).abbreviate(length).name();
    }
}
