package jp.ac.titech.c.se.stein.analyzer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TokenizerTest {
    private final RegexTokenizer regex = new RegexTokenizer();

    private static final String SOURCE = """
            class C {
                int x = 1;
            }
            """;

    private static byte[] blob(final String source) {
        return source.getBytes(StandardCharsets.UTF_8);
    }

    private static String texts(final List<Token> tokens) {
        return tokens.stream().map(Token::text).collect(Collectors.joining("|"));
    }

    @Test
    public void testRegexTokenization() {
        final List<Token> tokens = regex.tokenize("C.java", blob(SOURCE), null);
        assertEquals("class| |C| |{|\n    |int| |x| |=| |1|;|\n|}|\n", texts(tokens));
        // whitespace runs are tokens like any other, so the texts concatenate back to the source
        assertEquals(SOURCE, tokens.stream().map(Token::text).collect(Collectors.joining()));

        final Token first = tokens.get(0);
        assertEquals("class", first.text());
        assertEquals(1, first.line());
        assertEquals(1, first.column());
        assertEquals(0, first.start());
        assertNull(first.type());  // no grammar to take a type from

        // a token past a line break gets its position from the whitespace it followed
        final Token declared = tokens.stream().filter(t -> t.text().equals("int")).findFirst().orElseThrow();
        assertEquals(2, declared.line());
        assertEquals(5, declared.column());
        assertEquals(SOURCE.indexOf("int"), declared.start());
    }

    @Test
    public void testRegexTokenizerAcceptsAnyFile() {
        // the rules are lexical, so there is no language to recognize and none to report
        assertTrue(regex.accepts("C.java"));
        assertTrue(regex.accepts("Makefile"));
        assertNull(regex.languageOf("C.java"));
    }

    @Test
    public void testTokenizingExtractorIsATokenizer() {
        // a ModelExtractor.Tokenizing is a Tokenizer too, satisfied out of its own model. Judging the
        // language is what a parse backend buys with its whitespace: the tokens are typed, but they no
        // longer reconstruct the source.
        final List<Token> tokens = new TreeSitterAnalyzer().tokenize("C.java", blob(SOURCE), null);
        assertEquals("class|C|{|int|x|=|1|;|}", texts(tokens));
        assertEquals("CLASS", tokens.get(0).type());
    }
}
