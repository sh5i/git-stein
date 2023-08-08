package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.core.*;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.sha1.SHA1;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
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
        final Collection<? extends Module> modules = generate(entry, text, c);
        if (!modules.isEmpty()) {
            for (final Module m : modules) {
                final byte[] newBlob = m.getRawContent();
                log.debug("Generate module: {} (`{}...`) from {} {}", m.getFilename(), StringUtils.left(m.getContent().trim(), 8), entry, c);
                result.add(HotEntry.of(entry.getMode(), m.getFilename(), newBlob, entry.getDirectory()));
            }
            log.debug("Rewrite entry: {} -> {} entries {}", entry, result.size(), c);
        }
        return result;
    }

    protected abstract boolean accept(final String filename);

    protected abstract Collection<? extends Module> generate(final HotEntry.Single entry, final SourceText text, final Context c);

    public static String digest(final String name, final int length) {
        final SHA1 sha1 = SHA1.newInstance();
        sha1.update(name.getBytes());
        return ObjectId.fromRaw(sha1.digest()).abbreviate(length).name();
    }

    public interface Module {
        String getFilename();

        String getContent();

        default byte[] getRawContent() {
            return getContent().getBytes(StandardCharsets.UTF_8);
        }

        static Module of(final String filename, final String content) {
            return new SimpleModule(filename, content);
        }
    }

    @Value
    public static class SimpleModule implements Module {
        String filename, content;
    }
}
