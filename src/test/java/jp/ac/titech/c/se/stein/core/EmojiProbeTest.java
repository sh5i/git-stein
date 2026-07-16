package jp.ac.titech.c.se.stein.core;

import jp.ac.titech.c.se.stein.analyzer.TreeSitterAnalyzer;
import jp.ac.titech.c.se.stein.analyzer.Token;
import jp.ac.titech.c.se.stein.analyzer.TokenizingModel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A supplementary character (a 4-byte UTF-8 char, above the BMP) must not shift the byte offsets the
 * tree-sitter binding reports against the decoded string. The tree-sitter C core addresses source by
 * UTF-8 byte offset, and {@link SourceText#toCharIndex} maps those back assuming standard UTF-8; this
 * holds only if the binding hands the core standard UTF-8, not JNI modified UTF-8 (in which a
 * supplementary char is a 6-byte surrogate pair). A BMP character would not expose the difference.
 */
public class EmojiProbeTest {
    @Test
    public void supplementaryCharDoesNotShiftLaterTokens() {
        final String emoji = new String(new byte[] { (byte) 0xF0, (byte) 0x9F, (byte) 0x8E, (byte) 0x89 }, StandardCharsets.UTF_8);
        final String src = "class C { String a = \"" + emoji + "\"; int later = 42; }";
        final TokenizingModel a = new TreeSitterAnalyzer().extract("C.java", src.getBytes(StandardCharsets.UTF_8), null);
        final String tokens = a.getTokens(a.getRoot()).stream().map(Token::text).collect(Collectors.joining(" "));
        assertTrue(tokens.contains(emoji), tokens);           // the emoji itself is extracted intact
        assertTrue(tokens.contains("int later = 42"), tokens); // tokens after it are not shifted
    }
}
