package jp.ac.titech.c.se.stein.rewriter;

import jp.ac.titech.c.se.stein.core.HotEntry;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.filefilter.NotFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.FileFilter;

@Slf4j
@ToString
public class NameFilter implements FileFilter {
    @ToString.Exclude
    protected final WildcardFileFilter.Builder builder = WildcardFileFilter.builder();

    @Getter
    protected FileFilter filter = TrueFileFilter.TRUE;

    @SuppressWarnings("unused")
    @Option(names = "--pattern", paramLabel = "<glob;...>", description = "filename patterns for targets",
            arity = "0..*", split = ";")
    public void setPatterns(final String... wildcards) {
        builder.setWildcards(wildcards);
        filter = builder.get();
    }

    @SuppressWarnings("unused")
    @Option(names = {"-i", "--ignore-case"}, description = "perform case-insensitive matching")
    public void setCaseInsensitive(final boolean isIgnoringCase) {
        builder.setIoCase(isIgnoringCase ? IOCase.INSENSITIVE : IOCase.SENSITIVE);
        filter = builder.get();
    }

    @SuppressWarnings("unused")
    @Option(names = { "-V", "--invert-match" }, description = "select non-matching items for targets")
    public void setInvertMatch(final boolean isInvertMatch) {
        filter = isInvertMatch ? new NotFileFilter(builder.get()) : builder.get();
    }

    public boolean isDefault() {
        return filter instanceof TrueFileFilter;
    }

    public boolean accept(final HotEntry.Single entry) {
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
