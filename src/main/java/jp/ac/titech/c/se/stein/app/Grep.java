package jp.ac.titech.c.se.stein.app;

import java.io.File;
import java.io.FileFilter;

import jp.ac.titech.c.se.stein.core.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import jp.ac.titech.c.se.stein.core.Context;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Slf4j
@ToString
@Command(name = "grep", description = "Filter blob files by name")
public class Grep implements BlobTranslator {
    @SuppressWarnings("unused")
    @Option(names = "--pattern", paramLabel = "<glob;...>", description = "filename patterns for files to keep",
            required = true, arity = "1..*", split = ";")
    private void setPatterns(final String[] wildcards) {
        log.info("Set grep pattern: {}", (Object) wildcards);
        builder.setWildcards(wildcards);
        pattern = builder.get();
    }

    @SuppressWarnings("unused")
    @Option(names = {"-i", "--ignore-case"}, description = "perform case-insensitive matching")
    protected void setCaseInsensitive(final boolean isIgnoringCase) {
        builder.setIoCase(isIgnoringCase ? IOCase.INSENSITIVE : IOCase.SENSITIVE);
        pattern = builder.get();
    }

    @ToString.Exclude
    protected WildcardFileFilter.Builder builder = WildcardFileFilter.builder();

    protected FileFilter pattern;

    @Option(names = { "-V", "--invert-match" }, description = "select non-matching items to keep")
    protected boolean invertMatch = false;

    @Override
    public HotEntry rewriteBlobEntry(final HotEntry.SingleHotEntry entry, final Context c) {
        final File name = new File(entry.getName());
        if (pattern.accept(name) ^ invertMatch) {
            return entry;
        }
        log.debug("remove {}: name {}matched {}", entry, invertMatch ? "" : "not ", c);
        return HotEntry.empty();
    }
}
