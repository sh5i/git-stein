package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.ColdEntry;
import jp.ac.titech.c.se.stein.core.ColdEntry.HashEntry;
import jp.ac.titech.c.se.stein.core.ColdEntry.HashEntrySet;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import jp.ac.titech.c.se.stein.core.SourceText;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.sha1.SHA1;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
public abstract class Extractor extends RepositoryRewriter {
    @Option(names = "--no-original", negatable = true, description = "[ex]/include original files")
    protected boolean requiresOriginals = true;

    @Option(names = "--no-irrelevances", negatable = true, description = "[ex]/include non-target files")
    protected boolean requiresIrrelevances = true;

    @Override
    public ColdEntry rewriteEntry(final HashEntry entry, final Context c) {
        if (!entry.isBlob()) {
            return super.rewriteEntry(entry, c);
        }
        if (!accept(entry.name.toLowerCase())) {
            return requiresIrrelevances ? super.rewriteEntry(entry, c) : ColdEntry.EMPTY;
        }

        final HashEntrySet result = new HashEntrySet();
        if (requiresOriginals) {
            result.add((HashEntry) super.rewriteEntry(entry, c));
        }

        final SourceText text = SourceText.ofNormalized(source.readBlob(entry.id));
        final Collection<? extends Module> modules = generate(entry, text, c);
        if (!modules.isEmpty()) {
            for (final Module m : modules) {
                final ObjectId newId = target.writeBlob(m.getRawContent(), c);
                log.debug("Generate module: {} (`{}...`) [{}] from {} {}", m.getFilename(), StringUtils.left(m.getContent().trim(), 8), newId.name(), entry, c);
                result.add(new HashEntry(entry.mode, m.getFilename(), newId, entry.directory));
            }
            log.debug("Rewrite entry: {} -> %d entries {} {}", entry, result.size(), c);
        }
        return result;
    }

    protected abstract boolean accept(final String filename);

    protected abstract Collection<? extends Module> generate(final HashEntry entry, final SourceText text, final Context c);

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
