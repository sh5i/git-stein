package jp.ac.titech.c.se.stein.app.blob;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class TokenizeTest {
    static String tokens(String... tokens) {
        return String.join("\n", tokens) + "\n";
    }

    @Test
    public void testEncode() {
        assertEquals("", Tokenize.encode(""));
        assertEquals("hello\n", Tokenize.encode("hello"));
        assertEquals("   \n", Tokenize.encode("   "));
        assertEquals("日本語\n", Tokenize.encode("日本語"));
        assertEquals(tokens("a", "+", "b"), Tokenize.encode("a+b"));
        assertEquals(tokens("a", " ", "b"), Tokenize.encode("a b"));
        assertEquals(tokens("a", "\r", "b"), Tokenize.encode("a\nb"));
    }

    @Test
    public void testEncodeJava() {
        assertEquals(tokens(
                "public", " ", "class", " ", "A", " ", "{",
                "\r\t", "int", " ", "x", " ", "=", " ", "1", " ", "+", " ", "2", ";",
                "\r", "}",
                "\r"
        ), Tokenize.encode("public class A {\n\tint x = 1 + 2;\n}\n"));
    }

    @Test
    public void testEncodeJavaScript() {
        assertEquals(tokens(
                "const", " ", "sum", " ", "=", " ", "(", "a", ",", " ", "b", ")", " ", "=>", " ", "a", " ", "+", " ", "b", ";",
                "\r", "console", ".", "log", "(", "sum", "(", "3", ",", " ", "4", "));",
                "\r"
        ), Tokenize.encode("const sum = (a, b) => a + b;\nconsole.log(sum(3, 4));\n"));
    }

    @Test
    public void testRoundTrip() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/sample/Hello.java.v3")) {
            final String source = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertRoundTrip(source);
        }
    }

    private void assertRoundTrip(String source) {
        assertEquals(source, Untokenize.decode(Tokenize.encode(source)));
    }
}
