package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.HotEntry;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import lombok.ToString;
import picocli.CommandLine.Command;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@ToString
@Command(name = "@tokenize", description = "Encode source files to linetoken format")
public class Tokenize implements BlobTranslator {
    protected static final Pattern TOKEN = Pattern.compile(String.join("|",
  "\\s+", // whitespaces
            "\\w+", // word
            "[^\\w\\s]+" // symbols
    ));

    /**
     * Encodes the given source.
     */
    public static String encode(final String source) {
        return TOKEN.matcher(source).results()
                .map(m -> m.group().replace("\n", "\r") + "\n")
                .collect(Collectors.joining());
    }

    @Override
    public HotEntry rewriteBlobEntry(final HotEntry.Single entry, final Context c) {
        final String text = SourceText.of(entry.getBlob()).getContent();
        final String converted = encode(text);
        final byte[] newBlob = converted.getBytes(StandardCharsets.UTF_8);
        return entry.update(newBlob);
    }
}