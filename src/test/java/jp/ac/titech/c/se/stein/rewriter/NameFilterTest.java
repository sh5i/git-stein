package jp.ac.titech.c.se.stein.rewriter;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class NameFilterTest {
    @Test
    public void testDefault() {
        final NameFilter filter = new NameFilter();
        assertTrue(filter.isDefault());
        assertTrue(filter.accept("Hello.java"));
        assertTrue(filter.accept("README.md"));
    }

    @Test
    public void testSuffixPattern() {
        final NameFilter filter = new NameFilter("*.java");
        assertFalse(filter.isDefault());
        assertTrue(filter.accept("Hello.java"));
        assertFalse(filter.accept("Hello.JAVA"));
        assertFalse(filter.accept("README.md"));
    }

    @Test
    public void testMultiplePatterns() {
        final NameFilter filter = new NameFilter("*.java", "*.md");
        assertTrue(filter.accept("Hello.java"));
        assertTrue(filter.accept("README.md"));
        assertFalse(filter.accept("hello.txt"));
    }

    @Test
    public void testIgnoreCase() {
        final NameFilter filter = new NameFilter(true, "*.java");
        assertTrue(filter.accept("Hello.java"));
        assertTrue(filter.accept("Hello.JAVA"));
        assertFalse(filter.accept("README.md"));
    }

    @Test
    public void testInvertMatch() {
        final NameFilter filter = new NameFilter("*.java");
        filter.setInvertMatch(true);
        assertFalse(filter.accept("Hello.java"));
        assertTrue(filter.accept("README.md"));
    }

    @Test
    public void testWildcardPattern() {
        // non-suffix pattern falls back to WildcardFileFilter
        final NameFilter filter = new NameFilter("Hello*");
        assertTrue(filter.accept("Hello.java"));
        assertTrue(filter.accept("HelloWorld.java"));
        assertFalse(filter.accept("README.md"));
    }

    @Test
    public void testSetPatterns() {
        final NameFilter filter = new NameFilter();
        assertTrue(filter.accept("README.md"));

        filter.setPatterns("*.java");
        assertFalse(filter.accept("README.md"));
        assertTrue(filter.accept("Hello.java"));
    }
}
