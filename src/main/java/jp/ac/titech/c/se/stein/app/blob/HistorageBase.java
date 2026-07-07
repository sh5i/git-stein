package jp.ac.titech.c.se.stein.app.blob;

import java.util.List;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Option;

/**
 * Common skeleton for the Historage generators, which split a source blob into finer-grained
 * modules (one file per language object such as a class, method, or field). A subclass decides
 * which blobs it handles ({@link #accepts}) and how to split one into modules
 * ({@link #generateModules}); this base keeps the original file (unless {@code --no-original}),
 * appends the generated modules, and logs.
 */
@Slf4j
public abstract class HistorageBase implements BlobTranslator {
    @Option(names = "--no-original", negatable = true, description = "Exclude original files")
    protected boolean requiresOriginals = true;

    @Override
    public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
        if (!accepts(entry)) {
            return entry;
        }
        final List<? extends HotEntry> modules = generateModules(entry, c);
        final AnyHotEntry.Set result = AnyHotEntry.set();
        if (requiresOriginals) {
            result.add(entry);
        }
        for (final HotEntry module : modules) {
            log.debug("Generate submodule: {} from {} {}", module.getName(), entry, c);
            result.add(module);
        }
        if (!modules.isEmpty()) {
            log.debug("Rewrite entry: {} -> {} entries {}", entry, result.size(), c);
        }
        return result;
    }

    /**
     * Whether this generator handles the given blob (typically a file-extension check).
     */
    protected abstract boolean accepts(BlobEntry entry);

    /**
     * Splits the given (accepted) blob into finer-grained module entries. Returns an empty list
     * when there is nothing to split out.
     */
    protected abstract List<? extends HotEntry> generateModules(BlobEntry entry, Context c);
}
