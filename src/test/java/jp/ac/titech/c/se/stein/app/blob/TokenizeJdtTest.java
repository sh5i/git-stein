package jp.ac.titech.c.se.stein.app.blob;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static jp.ac.titech.c.se.stein.app.blob.TokenizeTest.tokens;
import static org.junit.jupiter.api.Assertions.*;

public class TokenizeJdtTest {

    @Test
    public void testEncode() {
        assertEquals("", TokenizeJdt.encode(""));

        assertEquals(tokens("int", " ", "x", " ", "=", " ", "1", ";"),
                TokenizeJdt.encode("int x = 1;"));
    }

    @Test
    public void testEncodePreservesComments() {
        // JDT scanner includes trailing newline in line comment token
        assertEquals(tokens("// comment\r", "int", " ", "x", ";"),
                TokenizeJdt.encode("// comment\nint x;"));
    }

    @Test
    public void testEncodePreservesStringLiteral() {
        assertEquals(tokens("String", " ", "s", " ", "=", " ", "\"hello\"", ";"),
                TokenizeJdt.encode("String s = \"hello\";"));
    }

    @Test
    public void testEncodeMultiline() {
        assertEquals(tokens("class", " ", "A", " ", "{", "\r", "}"),
                TokenizeJdt.encode("class A {\n}"));
    }

    @Test
    public void testRoundTrip() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/sample/Hello.java.v3")) {
            final String source = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertRoundTrip(source);
        }
    }

    private void assertRoundTrip(String source) {
        assertEquals(source, Untokenize.decode(TokenizeJdt.encode(source)));
    }
}
