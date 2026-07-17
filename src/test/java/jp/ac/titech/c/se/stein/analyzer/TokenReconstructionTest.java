package jp.ac.titech.c.se.stein.analyzer;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.util.ProcessRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A token stream must account for every non-whitespace character of its source, exactly once and in
 * order: stripped of whitespace, the concatenated token texts are the source stripped of whitespace.
 * Whitespace itself is the backend's to drop, and only whitespace.
 *
 * <p>A consumer walking the stream against the original file -- which is how a token-level blame view
 * puts authorship back onto real source -- has no other way to stay in step. A stream that loses a
 * construct also misreports what the code says: a token module that has dropped its {@code #ifdef}
 * shows conditional code as unconditional.</p>
 *
 * <p>Each element's tokens ({@link TokenizingModel#getTokens}) and the whole file's marked-up stream
 * ({@link TokenizingModel#walkTokens}) are separate walks in every backend, so both are checked.</p>
 */
public class TokenReconstructionTest {
    private final SrcmlAnalyzer srcml = new SrcmlAnalyzer();

    private static final String JAVA = """
            class A {
                /* a block
                   comment */
                int x = 1;
                int get() { return x; }
            }
            """;

    private static final String C_MACRO = """
            #define MAX(a, b) \\
                ((a) > (b) ? \\
                 (a) : (b))

            int main(void) {
                return MAX(1, 2);
            }
            """;

    private static final String C_IFDEF = """
            int compute(int x) {
            #ifdef DEBUG
                log_it(x);
            #endif
                return x * 2;
            }
            """;

    /**
     * Asserts that the analyzer's two token walks each reconstruct the source, up to whitespace.
     */
    private void assertReconstructs(final ModelExtractor.Tokenizing analyzer, final String filename,
                                    final String source) {
        final TokenizingModel model = analyzer.extract(filename, source.getBytes(StandardCharsets.UTF_8),
                Context.init());
        assertNotNull(model, filename);
        assertEquals(squeeze(source), squeeze(texts(model.getTokens(model.getRoot()))),
                filename + " via getTokens");

        final List<Token> walked = new ArrayList<>();
        model.walkTokens(new TokenizingModel.TokenVisitor() {
            @Override
            public void begin(final Element.Kind kind) {
            }

            @Override
            public void token(final Token token) {
                walked.add(token);
            }

            @Override
            public void end(final Element.Kind kind) {
            }
        });
        assertEquals(squeeze(source), squeeze(texts(walked)), filename + " via walkTokens");
    }

    private static String texts(final List<Token> tokens) {
        return tokens.stream().map(Token::text).collect(Collectors.joining());
    }

    /**
     * The text with every whitespace character removed: what both sides must agree on.
     */
    private static String squeeze(final String text) {
        return text.replaceAll("\\s+", "");
    }

    @Test
    public void testSrcmlReconstructsSource() {
        assumeTrue(ProcessRunner.isAvailable("srcml"), "srcml not available");
        assertReconstructs(srcml, "A.java", JAVA);
        // srcML puts the preprocessor in its own XML namespace; it is still the file's source
        assertReconstructs(srcml, "macro.c", C_MACRO);
        assertReconstructs(srcml, "compute.c", C_IFDEF);
    }
}
