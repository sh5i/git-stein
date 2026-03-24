package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import lombok.ToString;
import picocli.CommandLine.Command;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Encodes source files to linetoken format, where each token occupies one line.
 * Newlines within tokens are replaced with {@code \r} to preserve them.
 * The inverse operation is {@link Untokenize}.
 *
 * <p>Tokenization is language-agnostic, splitting on whitespace, word characters, and symbols.
 * For Java-aware tokenization, see {@link TokenizeViaJDT}.</p>
 */
@ToString
@Command(name = "@tokenize", description = "Encode source files to linetoken format")
public class Tokenize implements BlobTranslator {
    protected static final Pattern TOKEN = Pattern.compile(String.join("|",
            "\\s+", // whitespaces
            "\\w+", // word
            "[^\\w\\s]+" // symbols
    ));

    @Override
    public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
        final String text = SourceText.of(entry.getBlob()).getContent();
        return entry.update(encode(text));
    }

    /**
     * Encodes the given source to linetoken format.
     * Each token (whitespace, word, or symbol sequence) becomes one line,
     * with embedded newlines replaced by {@code \r}.
     */
    public static String encode(final String source) {
        return TOKEN.matcher(source).results()
                .map(m -> m.group().replace("\n", "\r") + "\n")
                .collect(Collectors.joining());
    }
}
