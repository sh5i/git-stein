package jp.ac.titech.c.se.stein.app.blob;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static jp.ac.titech.c.se.stein.app.blob.TokenizeTest.tokens;
import static org.junit.jupiter.api.Assertions.*;

public class TokenizeViaJDTTest {

    @Test
    public void testEncode() {
        assertEquals("", TokenizeViaJDT.encode(""));

        assertEquals(tokens("int", " ", "x", " ", "=", " ", "1", ";"),
                TokenizeViaJDT.encode("int x = 1;"));
    }

    @Test
    public void testEncodePreservesComments() {
        // JDT scanner includes trailing newline in line comment token
        assertEquals(tokens("// comment\r", "int", " ", "x", ";"),
                TokenizeViaJDT.encode("// comment\nint x;"));
    }

    @Test
    public void testEncodePreservesStringLiteral() {
        assertEquals(tokens("String", " ", "s", " ", "=", " ", "\"hello\"", ";"),
                TokenizeViaJDT.encode("String s = \"hello\";"));
    }

    @Test
    public void testEncodeMultiline() {
        assertEquals(tokens("class", " ", "A", " ", "{", "\r", "}"),
                TokenizeViaJDT.encode("class A {\n}"));
    }

    @Test
    public void testRoundTrip() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/sample/Hello.java.v3")) {
            final String source = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertRoundTrip(source);
        }
    }

    private void assertRoundTrip(String source) {
        assertEquals(source, Untokenize.decode(TokenizeViaJDT.encode(source)));
    }
}
