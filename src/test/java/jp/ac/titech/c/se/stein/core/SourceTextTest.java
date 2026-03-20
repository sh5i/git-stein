package jp.ac.titech.c.se.stein.core;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SourceTextTest {
    static final String SRC = "hello\n  world\n";
    final SourceText text = SourceText.of(SRC.getBytes(StandardCharsets.UTF_8));

    @Test
    public void testOf() {
        assertEquals(SRC, text.getContent());
        assertArrayEquals(SRC.getBytes(StandardCharsets.UTF_8), text.getRaw());
    }

    @Test
    public void testOfNormalized() {
        final SourceText crlf = SourceText.ofNormalized("hello\r\nworld\r".getBytes(StandardCharsets.UTF_8));
        assertEquals("hello\nworld\n", crlf.getContent());
    }

    @Test
    public void testGetFragmentOfLines() {
        assertEquals("hello\n", text.getFragmentOfLines(1, 1).getExactContent());
        assertEquals("  world\n", text.getFragmentOfLines(2, 2).getExactContent());
        assertEquals(SRC, text.getFragmentOfLines(1, 2).getExactContent());

        final SourceText noTrailing = SourceText.of("aaa\nbbb".getBytes(StandardCharsets.UTF_8));
        assertEquals("bbb", noTrailing.getFragmentOfLines(2, 2).getExactContent());
    }

    @Test
    public void testGetFragment() {
        // "world" at index 8..13
        final SourceText.Fragment f = text.getFragment(8, 13);
        assertEquals("world", f.getExactContent());
        assertEquals("world", f.toString());
        assertEquals("  world\n", f.getWiderContent());
        assertEquals("  ", f.getIndent());

        // "hello" at index 0..5, no leading space
        final SourceText.Fragment f2 = text.getFragment(0, 5);
        assertEquals("hello", f2.getExactContent());
        assertEquals("", f2.getIndent());
        assertEquals("hello\n", f2.getWiderContent());

        // wider content appends newline if missing
        final SourceText noNewline = SourceText.of("  hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("  hello\n", noNewline.getFragment(2, 7).getWiderContent());

        // tab indent
        final SourceText tabbed = SourceText.of("\thello\n".getBytes(StandardCharsets.UTF_8));
        assertEquals("\t", tabbed.getFragment(1, 6).getIndent());
    }

    // --- Sample source tests using JDT ---

    static Map<String, MethodDeclaration> parseMethods(String source) {
        final ASTParser parser = ASTParser.newParser(AST.JLS17);
        @SuppressWarnings("unchecked")
        final Map<String, String> options = DefaultCodeFormatterConstants.getEclipseDefaultSettings();
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_17);
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_17);
        parser.setCompilerOptions(options);
        parser.setSource(source.toCharArray());
        final CompilationUnit unit = (CompilationUnit) parser.createAST(null);

        final Map<String, MethodDeclaration> methods = new java.util.LinkedHashMap<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                methods.put(node.getName().getIdentifier(), node);
                return false;
            }
        });
        return methods;
    }

    @Test
    public void testWithSampleSource() throws IOException {
        final SourceText src;
        try (InputStream is = getClass().getResourceAsStream("/sample/Hello.java")) {
            src = SourceText.of(is.readAllBytes());
        }

        final MethodDeclaration node = parseMethods(src.getContent()).get("getCount");
        final SourceText.Fragment getCount = src.getFragment(node.getStartPosition(), node.getStartPosition() + node.getLength());
        assertEquals("public int getCount() {\n        return count;\n    }", getCount.getExactContent());
        assertEquals("    public int getCount() {\n        return count;\n    }\n", getCount.getWiderContent());
    }
}
