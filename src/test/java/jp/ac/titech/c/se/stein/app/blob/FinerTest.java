package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class FinerTest {
    private final Context c = Context.init();
    private final Finer app = new Finer();

    private static final String SOURCE = """
            class Person {
                private int length;
                public int getLength() {
                    if (length == 0) { return 1; }
                    return length;
                }
            }
            """;

    private Map<String, String> rewrite(final String name, final String source) {
        final AnyHotEntry result = app.rewriteBlobEntry(HotEntry.ofBlob(name, source), c);
        return result.stream().collect(Collectors.toMap(HotEntry::getName, e -> new String(((BlobEntry) e).getBlob())));
    }

    @Test
    public void testModuleNames() {
        // one file per method and field; classes are naming scopes only
        assertEquals(Set.of(
                "Person.java",
                "Person#length.fjava",
                "Person#getLength().mjava"), rewrite("Person.java", SOURCE).keySet());
    }

    @Test
    public void testMethodTokenSequence() {
        // the FinerGit token sequence with Heuristic 1 (context-refined structural tokens) and the
        // default Heuristic 2 (the method's parameter parentheses and body braces are omitted); note
        // the method-body brace type would be METHOD_DECLARATION_LBRACE, distinct from the if block's
        assertEquals("""
                public PUBLIC
                int INT
                getLength DECLARED_METHOD_NAME
                if IF
                ( IF_STATEMENT_LPAREN
                length VARIABLE_NAME
                == ==
                0 DECIMAL_INTEGER_LITERAL
                ) IF_STATEMENT_RPAREN
                { IF_STATEMENT_LBRACE
                return RETURN
                1 DECIMAL_INTEGER_LITERAL
                ; RETURN_STATEMENT_SEMICOLON
                } IF_STATEMENT_RBRACE
                return RETURN
                length VARIABLE_NAME
                ; RETURN_STATEMENT_SEMICOLON
                """, rewrite("Person.java", SOURCE).get("Person#getLength().mjava"));
    }

    @Test
    public void testHeuristic1CanBeDisabled() {
        // with Heuristic 1 off, each line is just the token, with no type annotation
        app.includesTokenType = false;
        final String method = rewrite("Person.java", SOURCE).get("Person#getLength().mjava");
        assertTrue(method.contains("getLength\n"), method);
        assertFalse(method.contains("DECLARED_METHOD_NAME"), method);
        assertFalse(method.contains("IF_STATEMENT_LPAREN"), method);
    }

    @Test
    public void testFrameKeptWhenNotOmitting() {
        // without Heuristic 2, the four frame tokens appear with their context-refined types
        app.omitsFrame = false;
        final String method = rewrite("Person.java", SOURCE).get("Person#getLength().mjava");
        assertTrue(method.contains("( FORMAL_PARAMETERS_LPAREN\n"), method);
        assertTrue(method.contains(") FORMAL_PARAMETERS_RPAREN\n"), method);
        assertTrue(method.contains("{ METHOD_DECLARATION_LBRACE\n"), method);
        assertTrue(method.contains("} METHOD_DECLARATION_RBRACE\n"), method);
    }

    @Test
    public void testIdentifierRolesFollowFinerGit() {
        // a declared method name, an invoked method name, and a variable of the same text get
        // distinct roles; type references become type names
        final String method = rewrite("C.java", """
                class C {
                    List total(List total) { return total.total(); }
                }
                """).get("C#total(List).mjava");
        assertTrue(method.contains("total DECLARED_METHOD_NAME\n"), method);  // the declaration
        assertTrue(method.contains("total INVOKED_METHOD_NAME\n"), method);   // the call total.total()
        assertTrue(method.contains("total VARIABLE_NAME\n"), method);         // the parameter and its use
        assertTrue(method.contains("List TYPE_NAME\n"), method);              // a type reference
    }

    @Test
    public void testFieldTokenSequence() {
        assertEquals("""
                private PRIVATE
                int INT
                length VARIABLE_NAME
                ; FIELD_DECLARATION_SEMICOLON
                """, rewrite("Person.java", SOURCE).get("Person#length.fjava"));
    }

    @Test
    public void testAbstractMethodSemicolonOmittedAsFrame() {
        // a bodyless method's terminating semicolon is a frame token (FinerGit Heuristic 2)
        final Map<String, String> entries = rewrite("I.java", """
                interface I {
                    int get();
                }
                """);
        final String method = entries.get("I#get().mjava");
        assertFalse(method.contains("SEMICOLON"), method);
    }

    @Test
    public void testMultiLanguageTokenization() {
        // @finer tokenizes every tree-sitter language; structural tokens are context-typed
        // generically (JavaScript here), while identifiers stay generic outside Java
        final String js = rewrite("s.js", """
                function add(a, b) {
                    if (a === 0) return b;
                    return a + b;
                }
                """).get("s!add(a,b).mjs");
        assertTrue(js.contains("function FUNCTION\n"), js);
        assertTrue(js.contains("if IF\n"), js);
        assertTrue(js.contains("( IF_STATEMENT_LPAREN\n"), js);     // structural typing works generically
        assertTrue(js.contains("a IDENTIFIER\n"), js);              // generic identifier (no Java role)
        assertFalse(js.contains("( FORMAL_PARAMETERS_LPAREN"), js); // the method frame is omitted

        // Python: no braces, colon-delimited; still one token per line with context-typed structure
        final String py = rewrite("s.py", """
                def add(a, b):
                    return a + b
                """).get("s!add(a,b).mpy");
        assertTrue(py.contains("def DEF\n"), py);
        assertTrue(py.contains("return RETURN\n"), py);
    }

    @Test
    public void testCommentsAreSkipped() {
        final String method = rewrite("C.java", """
                class C {
                    void m() {
                        // a line comment
                        /* a block comment */
                        foo();
                    }
                }
                """).get("C#m().mjava");
        assertFalse(method.contains("comment"), method);
        // a call's own parentheses are kept (only the method frame is omitted)
        assertTrue(method.contains("( ARGUMENT_LIST_LPAREN\n"), method);
    }
}
