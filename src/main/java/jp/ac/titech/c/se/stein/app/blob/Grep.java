package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import jp.ac.titech.c.se.stein.core.Context;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

@Slf4j
@ToString
@Command(name = "@grep", description = "Filter blob files by name")
public class Grep implements BlobTranslator {
    @Mixin
    private final NameFilter filter = new NameFilter();

    @Override
    public AnyHotEntry rewriteBlobEntry(final HotEntry entry, final Context c) {
        if (!filter.accept(entry)) {
            log.debug("remove {}: filename unaccepted {}", entry, c);
            return AnyHotEntry.empty();
        }
        return entry;
    }
}
