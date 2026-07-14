package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.util.ProcessRunner;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class HistorageTokenTest {
    private final Context c = Context.init();
    private final Historage app = tokenMode();

    private static Historage tokenMode() {
        final Historage h = new Historage().backends(Historage.BackendType.ts);
        h.tokens = true;
        return h;
    }

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
    public void testCppTemplateHeaderIsPartOfTheMethodTokens() {
        // the C++ core range widens over the template header, and the token stream follows it, so the
        // token module and the raw module agree on the element's boundary
        final Map<String, String> entries = rewrite("t.cpp", """
                template<class T> T id(T x) { return x; }
                """);
        assertEquals("""
                template TEMPLATE
                < TEMPLATE_PARAMETER_LIST_LT
                class CLASS
                T TYPE_NAME
                > TEMPLATE_PARAMETER_LIST_GT
                T TYPE_NAME
                id VARIABLE_NAME
                ( PARAMETER_LIST_LPAREN
                T TYPE_NAME
                x VARIABLE_NAME
                ) PARAMETER_LIST_RPAREN
                return RETURN
                x VARIABLE_NAME
                ; RETURN_STATEMENT_SEMICOLON
                """, entries.get("t!id(T).mcpp"));
    }

    @Test
    public void testMethodTokenSequence() {
        // the FinerGit token sequence with Heuristic 1 (context-refined structural tokens) and the
        // default Heuristic 2 (the method's parameter parentheses and body braces are omitted); note
        // the method-body brace type would be METHOD_DECLARATION_LBRACE, distinct from the if block's
        assertEquals("""
                public PUBLIC
                int INT
                getLength METHOD_DECLARATION_NAME
                if IF
                ( IF_STATEMENT_LPAREN
                length VARIABLE_NAME
                == BINARY_EXPRESSION_EQEQ
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
    public void testTokenTypesFromGrammaticalPosition() {
        // each occurrence of the same text gets its role from the non-terminal that contains it:
        // the method's declared name, the method it invokes, its parameter, and a variable use are
        // all distinct; type references become type names
        final String method = rewrite("C.java", """
                class C {
                    List total(List total) { return total.total(); }
                }
                """).get("C#total(List).mjava");
        assertTrue(method.contains("total METHOD_DECLARATION_NAME\n"), method);  // the declaration
        assertTrue(method.contains("total METHOD_INVOCATION_NAME\n"), method);   // the call total.total()
        assertTrue(method.contains("total FORMAL_PARAMETER_NAME\n"), method);    // the parameter
        assertTrue(method.contains("total VARIABLE_NAME\n"), method);            // the receiver use
        assertTrue(method.contains("List TYPE_NAME\n"), method);                 // a type reference
    }

    @Test
    public void testFieldTokenSequence() {
        assertEquals("""
                private PRIVATE
                int INT
                length VARIABLE_DECLARATOR_NAME
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
        // @historage --tokens tokenizes every tree-sitter language; structural tokens are context-typed and
        // identifiers are refined generically from the grammar's field labels (declared/invoked/
        // variable), as in JavaScript here
        final String js = rewrite("s.js", """
                function add(a, b) {
                    if (a === 0) return help(b);
                    return a + b;
                }
                """).get("s!add(a,b).mjs");
        assertTrue(js.contains("add FUNCTION_DECLARATION_NAME\n"), js);  // the function's name
        assertTrue(js.contains("help CALL_EXPRESSION_FUNCTION\n"), js);  // a call
        assertTrue(js.contains("a VARIABLE_NAME\n"), js);                // a variable use
        assertTrue(js.contains("( IF_STATEMENT_LPAREN\n"), js);          // structural typing works generically
        assertFalse(js.contains("( FORMAL_PARAMETERS_LPAREN"), js);      // the method frame is omitted

        // Python: no braces, colon-delimited; the same containment-derived typing applies, using
        // Python's own non-terminal names
        final String py = rewrite("s.py", """
                def add(a, b):
                    return help(a) + b
                """).get("s!add(a,b).mpy");
        assertTrue(py.contains("add FUNCTION_DEFINITION_NAME\n"), py);
        assertTrue(py.contains("help CALL_FUNCTION\n"), py);
        assertTrue(py.contains("b VARIABLE_NAME\n"), py);
    }

    @Test
    public void testTokensDisablesNonTokenizingBackend() {
        // --tokens keeps only the tokenizing backends; with only jdt selected none qualifies, so the
        // backend selection is rejected at startup
        final Historage jdtTokens = new Historage().backends(Historage.BackendType.jdt);
        jdtTokens.tokens = true;
        assertThrows(IllegalArgumentException.class, () -> jdtTokens.setUp(c));
    }

    @Test
    public void testSrcmlTokenSequence() {
        assumeTrue(ProcessRunner.isAvailable("srcml"), "srcml not available");
        final Historage h = new Historage().backends(Historage.BackendType.srcml);
        h.tokens = true;
        final AnyHotEntry result = h.rewriteBlobEntry(HotEntry.ofBlob("Person.java", """
                class Person {
                    // a comment
                    public int getLength() {
                        return 42;
                    }
                }
                """), c);
        final Map<String, String> modules = result.stream()
                .collect(Collectors.toMap(HotEntry::getName, e -> new String(((BlobEntry) e).getBlob())));
        // srcML emits a FinerGit-style token sequence too, typed by srcML element names; the comment is
        // skipped and the method's own parameter parentheses and body braces are omitted (Heuristic 2)
        assertEquals("""
                public specifier
                int name
                getLength name
                return return
                42 literal
                ; return
                """, modules.get("Person#getLength().mjava"));
    }

    @Test
    public void testCommentDisposition() {
        final String source = """
                class C {
                    void m() {
                        // a line comment
                        /* a block comment */
                        foo();
                    }
                }
                """;
        // by default a comment is a token like any other, kept in the stream
        final String kept = rewrite("C.java", source).get("C#m().mjava");
        assertTrue(kept.contains("comment"), kept);

        // --comment=strip drops the comment tokens
        final Historage h = tokenMode();
        h.commentMode = Historage.CommentMode.strip;
        final String method = h.rewriteBlobEntry(HotEntry.ofBlob("C.java", source), c).stream()
                .collect(Collectors.toMap(HotEntry::getName, e -> new String(((BlobEntry) e).getBlob())))
                .get("C#m().mjava");
        assertFalse(method.contains("comment"), method);
        // a call's own parentheses are kept (only the method frame is omitted)
        assertTrue(method.contains("( ARGUMENT_LIST_LPAREN\n"), method);
    }
}
