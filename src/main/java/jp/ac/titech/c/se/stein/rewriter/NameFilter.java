package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.entry.HotEntry;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.filefilter.*;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileFilter;
import java.util.stream.Stream;

@Slf4j
@ToString
public class NameFilter implements FileFilter {
    @Getter
    protected IOFileFilter filter = TrueFileFilter.TRUE;

    @SuppressWarnings("unused")
    @Option(names = "--pattern", paramLabel = "<g>", description = "filename patterns for targets", split = ";")
    public void setPatterns(final String... patterns) {
        this.patterns = patterns;
        updateFilter();
    }
    protected String[] patterns;

    @SuppressWarnings("unused")
    @Option(names = {"-i", "--ignore-case"}, description = "perform case-insensitive matching")
    public void setIgnoringCase(final boolean isIgnoringCase) {
        this.isIgnoringCase = isIgnoringCase;
        updateFilter();
    }
    protected boolean isIgnoringCase = false;

    @SuppressWarnings("unused")
    @Option(names = {"-V", "--invert-match"}, description = "select non-matching items for targets")
    public void setInvertMatch(final boolean isInvertMatch) {
        this.invertMatch = isInvertMatch;
        updateFilter();
    }
    protected boolean invertMatch = false;

    protected void updateFilter() {
        if (patterns == null) {
            filter = TrueFileFilter.TRUE;
        } else if (isSuffixFilterCompatible(patterns)) {
            final String[] suffixes = Stream.of(patterns).map(s -> s.substring(1)).toArray(String[]::new);
            filter = new SuffixFileFilter(suffixes, isIgnoringCase ? IOCase.INSENSITIVE : IOCase.SENSITIVE);
        } else {
            filter = WildcardFileFilter.builder()
                    .setWildcards(patterns)
                    .setIoCase(isIgnoringCase ? IOCase.INSENSITIVE : IOCase.SENSITIVE)
                    .get();
        }
        if (invertMatch) {
            filter = filter.negate();
        }
    }

    protected boolean isSuffixFilterCompatible(final String[] patterns) {
        return Stream.of(patterns).allMatch(p -> p.matches("\\*\\.\\w+"));
    }

    public NameFilter() {}

    public NameFilter(final boolean isIgnoringCase, final String... patterns) {
        this.setIgnoringCase(isIgnoringCase);
        this.setPatterns(patterns);
    }

    public NameFilter(final String... patterns) {
        this(false, patterns);
    }

    public boolean isDefault() {
        return filter instanceof TrueFileFilter;
    }

    public boolean accept(final HotEntry entry) {
        return filter.accept(new File(entry.getName()));
    }
    public boolean accept(final String pathname) {
        return filter.accept(new File(pathname));
    }

    @Override
    public boolean accept(final File pathname) {
        return filter.accept(pathname);
    }
}
