package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;

@Slf4j
@ToString
@Command(name = "@size-filter", description = "Remove large files")
public class SizeFilter implements BlobTranslator {
    @Option(names = "--size", paramLabel = "<num>{,K,M,G}", description = "remove files larger than the size",
            required = true, converter = SizeConverter.class)
    protected long maxSize = -1L;

    @Option(names = { "-V", "--invert-match" }, description = "filter non-matching items")
    protected boolean invertMatch;

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

    @Override
    public HotEntry rewriteBlobEntry(final HotEntry.SingleHotEntry entry, final Context c) {
        final long size = entry.getBlobSize();
        if ((size >= maxSize) ^ invertMatch) {
            log.debug("remove {}: size ({}; {}B) {}exceeded {}", entry, FileUtils.byteCountToDisplaySize(size), size, invertMatch ? "not " : "", c);
            return HotEntry.empty();
        }
        return entry;
    }
}
