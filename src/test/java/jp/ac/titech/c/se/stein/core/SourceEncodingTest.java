package jp.ac.titech.c.se.stein.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SourceEncodingTest {
    // é (U+00E9), and its lone ISO-8859-1 byte 0xE9, built from bytes so this source file's own
    // encoding cannot confound the test
    private static final String EACUTE = new String(new byte[] { (byte) 0xE9 }, StandardCharsets.ISO_8859_1);

    private static byte[] latin1(final String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    public void testMagicCommentOnFirstLine() {
        // the declared codec (latin-1, resolved via the hyphen-stripping retry) decodes 0xE9 as é
        final byte[] bytes = latin1("# -*- coding: latin-1 -*-\ns = \"" + EACUTE + "\"\n");
        assertTrue(SourceEncoding.decodeWithMagicComment(bytes).contains(EACUTE));
    }

    @Test
    public void testMagicCommentOnSecondLine() {
        // the first two lines are scanned; the second is read even without a shebang (a simplification --
        // strict Ruby reads line 2 only after a shebang -- but harmless in practice)
        final byte[] bytes = latin1("# a comment\n# coding: iso-8859-1\ns = \"" + EACUTE + "\"\n");
        assertTrue(SourceEncoding.decodeWithMagicComment(bytes).contains(EACUTE));
    }

    @Test
    public void testDefaultsToUtf8WithoutMagicComment() {
        // no declaration -> UTF-8, under which the UTF-8-encoded é round-trips
        final byte[] bytes = ("s = \"" + EACUTE + "\"\n").getBytes(StandardCharsets.UTF_8);
        assertTrue(SourceEncoding.decodeWithMagicComment(bytes).contains(EACUTE));
    }

    @Test
    public void testGenericKeepsValidUtf8WithEmoji() {
        // U+1F389 (a 4-byte char), which the statistical detector is unreliable on; UTF-8-first keeps it
        final String tada = new String(new byte[] { (byte) 0xF0, (byte) 0x9F, (byte) 0x8E, (byte) 0x89 }, StandardCharsets.UTF_8);
        final byte[] bytes = ("const s = \"done " + tada + "\";\n").getBytes(StandardCharsets.UTF_8);
        assertTrue(SourceEncoding.decode(bytes).contains(tada));
    }

    @Test
    public void testGenericDetectsIso2022Jp() throws Exception {
        // ISO-2022-JP is 7-bit, hence valid UTF-8, but is not UTF-8 text; the ESC guard sends it to the
        // detector instead of the UTF-8 shortcut, so the Japanese round-trips instead of becoming garbage
        final String jp = new String(new byte[] {
                (byte) 0xE6, (byte) 0x97, (byte) 0xA5, (byte) 0xE6, (byte) 0x9C, (byte) 0xAC,
                (byte) 0xE8, (byte) 0xAA, (byte) 0x9E, (byte) 0xE3, (byte) 0x83, (byte) 0x86,
                (byte) 0xE3, (byte) 0x82, (byte) 0xB9, (byte) 0xE3, (byte) 0x83, (byte) 0x88 }, StandardCharsets.UTF_8);
        final byte[] bytes = jp.getBytes("ISO-2022-JP");
        assertEquals(jp, SourceEncoding.decode(bytes));
    }

    @Test
    public void testStripsUtf8Bom() {
        final byte[] bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
        final byte[] bytes = concat(bom, "x = 1\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("x = 1\n", SourceEncoding.decode(bytes));
        assertEquals("x = 1\n", SourceEncoding.decodeWithMagicComment(bytes));
    }

    private static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }
}
