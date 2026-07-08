package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CregitTreeSitterTest {
    private final Context c = Context.init();
    private final CregitTreeSitter app = new CregitTreeSitter();

    private String convert(final String name, final String source) {
        final AnyHotEntry result = app.rewriteBlobEntry(HotEntry.ofBlob(name, source), c);
        return new String(((BlobEntry) result.stream().findFirst().orElseThrow()).getBlob());
    }

    @Test
    public void testJavaTokenStream() {
        // one token per line as type|content; each class/method/field is wrapped in begin_/end_ by its
        // source range, and each structural token is typed by its enclosing non-terminal (a method-body
        // brace differs from a class-body brace)
        assertEquals("""
                begin_unit|language:Java;cregit-version:0.0.1
                begin_class
                CLASS|class
                CLASS_DECLARATION_NAME|A
                CLASS_BODY_LBRACE|{
                begin_field
                INT|int
                VARIABLE_DECLARATOR_NAME|x
                FIELD_DECLARATION_SEMICOLON|;
                end_field
                begin_method
                INT|int
                METHOD_DECLARATION_NAME|get
                FORMAL_PARAMETERS_LPAREN|(
                FORMAL_PARAMETERS_RPAREN|)
                METHOD_DECLARATION_LBRACE|{
                RETURN|return
                VARIABLE_NAME|x
                RETURN_STATEMENT_SEMICOLON|;
                METHOD_DECLARATION_RBRACE|}
                end_method
                CLASS_BODY_RBRACE|}
                end_class
                end_unit
                """, convert("A.java", "class A { int x; int get() { return x; } }"));
    }

    @Test
    public void testCommentsKeptAcrossLanguages() {
        // unlike @finer, cregit keeps comments; the language name is in the header; a Python function
        // is a method, so its tokens are wrapped in begin_method/end_method
        final String py = convert("s.py", "def add(a, b):\n    # sum\n    return a + b\n");
        assertTrue(py.startsWith("begin_unit|language:Python;cregit-version:0.0.1\nbegin_method\n"), py);
        assertTrue(py.contains("FUNCTION_DEFINITION_NAME|add\n"), py);
        assertTrue(py.contains("COMMENT|# sum\n"), py);
        // operators and punctuation are named from their characters and syntactic context
        assertTrue(py.contains("FUNCTION_DEFINITION_COLON|:\n"), py);
        assertTrue(py.contains("BINARY_OPERATOR_PLUS|+\n"), py);
        assertTrue(py.endsWith("end_method\nend_unit\n"), py);
    }

    @Test
    public void testPosition() {
        app.position = true;
        final String out = convert("A.java", "class A { int x; int get() { return x; } }");
        // structural markers carry a placeholder position; each token carries its 1-based line:column
        assertTrue(out.startsWith("-:-|begin_unit|language:Java;cregit-version:0.0.1\n-:-|begin_class\n"), out);
        assertTrue(out.contains("\n1:1|CLASS|class\n"), out);
        assertTrue(out.contains("1:7|CLASS_DECLARATION_NAME|A\n"), out);
        assertTrue(out.endsWith("-:-|end_class\n-:-|end_unit\n"), out);
    }

    @Test
    public void testStructurelessLanguageTokenizes() {
        // HTML has no class/method/field, but cregit still tokenizes the whole file, with no begin_
        // markers — this is where HTML (and other structureless languages) support lives
        final String html = convert("page.html", "<div id=\"a\">hi</div>\n");
        assertTrue(html.startsWith("begin_unit|language:HTML;cregit-version:0.0.1\n"), html);
        assertTrue(html.contains("TAG_NAME|div\n"), html);
        assertTrue(html.contains("TEXT|hi\n"), html);
        assertFalse(html.contains("begin_class"), html);
        assertFalse(html.contains("begin_method"), html);
        assertTrue(html.endsWith("end_unit\n"), html);
    }

    @Test
    public void testUnsupportedFileUnchanged() {
        // a file no tree-sitter language handles is returned untouched
        assertEquals("# hello\n", convert("README.md", "# hello\n"));
    }
}
