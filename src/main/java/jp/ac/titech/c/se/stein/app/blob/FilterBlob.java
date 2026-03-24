package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import jp.ac.titech.c.se.stein.util.SizeConverter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import jp.ac.titech.c.se.stein.core.Context;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;


/**
 * Filters (removes) blob entries by filename pattern and/or size threshold.
 */
@Slf4j
@ToString
@Command(name = "@filter", description = "Filter blobs by name and/or size")
public class FilterBlob implements BlobTranslator {
    @Mixin
    protected final NameFilter nameFilter = new NameFilter();

    @Option(names = "--size", paramLabel = "<num>{,K,M,G}", description = "threshold for size upperbound",
            converter = SizeConverter.class)
    protected long maxSize = -1L;

    @Override
    public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
        // name
        if (nameFilter.getPatterns() != null) {
            if (!nameFilter.accept(entry)) {
                log.debug("remove {}: filename unaccepted {}", entry, c);
                return AnyHotEntry.empty();
            }
        }

        // size
        if (maxSize >= 0) {
            final long size = entry.getBlobSize();
            final boolean invertMatch = nameFilter.isInvertMatch();   // notice: here abuses NameFilter.invertMatch for size
            if ((size > maxSize) ^ invertMatch) {
                log.debug("remove {}: size ({}; {}B) {} limit {}", entry, FileUtils.byteCountToDisplaySize(size), size, invertMatch ? "below" : "exceeded", c);
                return AnyHotEntry.empty();
            }
        }

        return entry;
    }
}
