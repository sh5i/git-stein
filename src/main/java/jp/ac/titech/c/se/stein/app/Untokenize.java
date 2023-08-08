package jp.ac.titech.c.se.stein.app;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.HotEntry;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.io.File;
import java.nio.charset.StandardCharsets;

@Slf4j
@ToString
@Command(name = "@untokenize", description = "Restore linetoken format to its original")
public class Untokenize implements BlobTranslator {
    @Mixin
    protected final NameFilter filter = new NameFilter();

    /**
     * Decodes the given linetoken source.
     */
    public static String decode(final String source) {
        return source.replace("\n", "").replace("\r", "\n");
    }

    @Override
    public HotEntry rewriteBlobEntry(final HotEntry.SingleHotEntry entry, final Context c) {
        if (!filter.accept(new File(entry.getName()))) {
            return entry;
        }
        final String text = SourceText.of(entry.getBlob()).getContent();
        final byte[] newBlob = decode(text).getBytes(StandardCharsets.UTF_8);
        return entry.update(newBlob);
    }
}
