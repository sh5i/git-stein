package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.analyzer.RegexTokenizer;
import jp.ac.titech.c.se.stein.analyzer.Token;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import lombok.ToString;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Encodes source files to linetoken format, where each token occupies one line.
 * Newlines within tokens are replaced with {@code \r} to preserve them.
 * The inverse operation is {@link Untokenize}.
 *
 * <p>Tokenization is language-agnostic ({@link RegexTokenizer}), splitting on whitespace, word
 * characters, and symbols. For Java-aware tokenization, see {@link TokenizeJdt}.</p>
 */
@ToString
@Command(name = "@tokenize", description = "Encode source files to linetoken format")
public class Tokenize implements BlobTranslator {
    private static final RegexTokenizer TOKENIZER = new RegexTokenizer();

    @Override
    public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
        return entry.update(render(TOKENIZER.tokenize(entry.getName(), entry.getBlob(), c)));
    }

    /**
     * Encodes the given source to linetoken format.
     * Each token (whitespace, word, or symbol sequence) becomes one line,
     * with embedded newlines replaced by {@code \r}.
     */
    public static String encode(final String source) {
        return render(TOKENIZER.tokenize(source));
    }

    /**
     * Renders a token stream to linetoken format: one line per token, each line break inside a token
     * replaced by {@code \r} so that the token keeps to its own line.
     */
    private static String render(final List<Token> tokens) {
        return tokens.stream()
                .map(t -> t.text().replace("\n", "\r") + "\n")
                .collect(Collectors.joining());
    }
}
