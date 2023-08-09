package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import lombok.ToString;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.nio.charset.StandardCharsets;

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
    public AnyHotEntry rewriteBlobEntry(final HotEntry entry, final Context c) {
        if (!filter.accept(entry)) {
            return entry;
        }
        final String text = SourceText.of(entry.getBlob()).getContent();
        final byte[] newBlob = decode(text).getBytes(StandardCharsets.UTF_8);
        return entry.update(newBlob);
    }
}
