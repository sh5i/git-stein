package jp.ac.titech.c.se.stein.analyzer.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class FormatUtilsTest {
    @Test
    public void testStripsCommonSpaceIndent() {
        assertEquals("/**\n * doc\n */", FormatUtils.dedent("    /**\n     * doc\n     */"));
    }

    @Test
    public void testStripsCommonTabIndent() {
        assertEquals("/**\n * doc", FormatUtils.dedent("\t/**\n\t * doc"));
    }

    @Test
    public void testStripsOnlyTheSharedPrefixNotEqualColumnCounts() {
        // both lines are indented by two whitespace columns but of different kinds; only the one shared
        // space is stripped, so a tab is never mistaken for two spaces
        assertEquals(" a\n\tb", FormatUtils.dedent("  a\n \tb"));
    }

    @Test
    public void testKeepsTextWhenNoSharedPrefix() {
        assertEquals("\ta\n b", FormatUtils.dedent("\ta\n b"));
    }

    @Test
    public void testIgnoresBlankLinesWhenComputingTheIndent() {
        assertEquals("a\n\nb", FormatUtils.dedent("  a\n\n  b"));
    }

    @Test
    public void testLeavesUnindentedTextUnchanged() {
        assertEquals("a\nb", FormatUtils.dedent("a\nb"));
    }
}
