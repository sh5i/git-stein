package jp.ac.titech.c.se.stein.app;

import java.io.File;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.eclipse.jgit.lib.ObjectId;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;

@Slf4j
@Command(name = "Cleaner", description = "Remove blob files")
public class Cleaner extends RepositoryRewriter {
    @Option(names = "--name", paramLabel = "<glob>", description = "remove files that matches the pattern",
            arity = "0..*", converter = FilterConverter.class)
    protected IOFileFilter[] filters;

    @Option(names = "--size", paramLabel = "<num>{,K,M,G}", description = "remove files larger than the size",
            converter = SizeConverter.class)
    protected long maxSize = -1L;

    @Option(names = { "-V", "--invert-match" }, description = "filter non-matching items")
    protected boolean invertMatch;

    public static class FilterConverter implements ITypeConverter<IOFileFilter> {
        @Override
        public IOFileFilter convert(final String value) {
            return new WildcardFileFilter(value);
        }
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

    @Override
    protected ObjectId rewriteBlob(final ObjectId blobId, final Context c) {
        if (filters.length > 0) {
            final File name = new File(c.getEntry().name);
            for (final IOFileFilter f : filters) {
                if (f.accept(name) ^ invertMatch) {
                    log.debug("remove {}: name ({}) {}matched {}", blobId.name(), name, invertMatch ? "not " : "", c);
                    return RepositoryRewriter.ZERO;
                }
            }
        }
        if (maxSize >= 0) {
            final long size = source.getBlobSize(blobId, c);
            if ((size >= maxSize) ^ invertMatch) {
                log.debug("remove {}: size ({}; {}B) {}exceeded {}", blobId.name(), FileUtils.byteCountToDisplaySize(size), size, invertMatch ? "not " : "", c);
                return RepositoryRewriter.ZERO;
            }
        }
        return super.rewriteBlob(blobId, c);
    }

    public static void main(final String[] args) {
        Application.execute(new Cleaner(), args);
    }
}
