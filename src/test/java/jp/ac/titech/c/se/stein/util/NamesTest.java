package jp.ac.titech.c.se.stein.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class NamesTest {
    @Test
    public void testEscape() {
        // whitespace → ~, trim
        assertEquals("int~int", Names.escape("int int"));
        assertEquals("int", Names.escape("  int  "));
        assertEquals("", Names.escape("   "));

        // < > → [ ]
        assertEquals("Map[String,~List[int]]", Names.escape("Map<String, List<int>>"));

        // ? → #
        assertEquals("List[#~extends~T]", Names.escape("List<? extends T>"));

        // : → ;
        assertEquals("Map.Entry[K;V]", Names.escape("Map.Entry<K:V>"));

        // " → '
        assertEquals("'hello'", Names.escape("\"hello\""));

        // / → %, \ → %
        assertEquals("a%b%c", Names.escape("a/b\\c"));

        // | → !
        assertEquals("a!b", Names.escape("a|b"));

        // * → +
        assertEquals("T+", Names.escape("T*"));

        // control characters removed
        assertEquals("ab", Names.escape("ab"));

        // combined: realistic signature
        assertEquals("void~foo(int,~String)", Names.escape("void foo(int, String)"));
    }
}
