package jp.ac.titech.c.se.stein.app;

import java.io.File;

import jp.ac.titech.c.se.stein.core.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import jp.ac.titech.c.se.stein.core.Context;
import picocli.CommandLine.Command;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.Option;

@Slf4j
@ToString
@Command(name = "filter", description = "Remove blob files")
public class Filter implements BlobTranslator {
    @Option(names = "--name", paramLabel = "<glob>", description = "remove files that matches the pattern",
            arity = "0..*", converter = FilterConverter.class)
    protected IOFileFilter[] filters;

    @Option(names = { "-V", "--invert-match" }, description = "filter non-matching items")
    protected boolean invertMatch;

    public static class FilterConverter implements ITypeConverter<IOFileFilter> {
        @Override
        public IOFileFilter convert(final String value) {
            return new WildcardFileFilter(value);
        }
    }

    @Override
    public HotEntry rewriteBlobEntry(final HotEntry.SingleHotEntry entry, final Context c) {
        if (filters.length > 0) {
            final File name = new File(entry.getName());
            for (final IOFileFilter f : filters) {
                if (f.accept(name) ^ invertMatch) {
                    log.debug("remove {}: name {}matched {}", entry, invertMatch ? "not " : "", c);
                    return HotEntry.empty();
                }
            }
        }
        return entry;
    }
}
