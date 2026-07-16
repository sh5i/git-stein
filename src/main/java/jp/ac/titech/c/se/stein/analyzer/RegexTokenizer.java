package jp.ac.titech.c.se.stein.analyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A lexical tokenizer that splits text into runs of whitespace, word characters, and symbols. It
 * judges nothing about the language -- no keyword, no literal, no comment -- and that is what buys it
 * its two properties: it accepts every file, and it keeps the whitespace, so its token texts
 * concatenate back to the exact input. Its tokens are untyped, having no grammar to take a type from.
 */
public class RegexTokenizer implements Tokenizer {
    private static final Pattern TOKEN = Pattern.compile(String.join("|",
            "\\s+", // whitespaces
            "\\w+", // word
            "[^\\w\\s]+" // symbols
    ));

    /**
     * Accepts every file: the rules are lexical, so there is no language to recognize.
     */
    @Override
    public boolean accepts(final String filename) {
        return true;
    }

    @Override
    public List<Token> tokenize(final String filename, final byte[] blob, final Context c) {
        return tokenize(SourceText.of(blob).getContent());
    }

    /**
     * Tokenizes the given source, in source order.
     *
     * @param source the text to tokenize, taken as it is (line breaks are not normalized)
     * @return the tokens, whose texts concatenate back to the source
     */
    public List<Token> tokenize(final String source) {
        final List<Token> result = new ArrayList<>();
        final Matcher matcher = TOKEN.matcher(source);
        int line = 1;
        int lineStart = 0;
        while (matcher.find()) {
            final String text = matcher.group();
            result.add(new Token(text, null, line, matcher.start() - lineStart + 1, matcher.start()));
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    line++;
                    lineStart = matcher.start() + i + 1;
                }
            }
        }
        return result;
    }
}
