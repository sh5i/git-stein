package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import jp.ac.titech.c.se.stein.core.Context;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;


@Slf4j
@ToString
@Command(name = "@filter", description = "Filter blobs by name and/or size")
public class FilterBlob implements BlobTranslator {
    @Mixin
    private final NameFilter nameFilter = new NameFilter();

    @Option(names = "--size", paramLabel = "<num>{,K,M,G}", description = "threshold for size upperbound",
            converter = SizeConverter.class)
    protected long maxSize = -1L;

    @Override
    public AnyHotEntry rewriteBlobEntry(final HotEntry entry, final Context c) {
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

    public static class SizeConverter implements ITypeConverter<Long> {
        @Override
        public Long convert(final String value) {
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Empty value is given");
            }
            final int len = value.length();
            final char unit = Character.toUpperCase(value.charAt(len - 1));
            final String num = value.substring(0, len - 1);
            switch (unit) {
                case 'B':
                    return convert(num);
                case 'K':
                    return displaySizeToByteCount(num, 1024);
                case 'M':
                    return displaySizeToByteCount(num, 1024 * 1024);
                case 'G':
                    return displaySizeToByteCount(num, 1024 * 1024 * 1024);
                default:
                    return displaySizeToByteCount(value, 1);
            }
        }

        protected long displaySizeToByteCount(final String value, final long base) {
            if (value.contains(".")) {
                return (long) (Double.parseDouble(value) * base);
            } else {
                return Long.parseLong(value) * base;
            }
        }
    }
}
