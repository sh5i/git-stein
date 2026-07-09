package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.analyzer.SrcmlAnalyzer;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import jp.ac.titech.c.se.stein.util.ProcessRunner;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class CregitTest {
    static RepositoryAccess source, result;

    @BeforeAll
    static void setUp() throws IOException {
        source = TestRepo.createSample();
    }

    @AfterAll
    static void tearDown() {
        if (result != null) {
            result.close();
        }
        if (source != null) {
            source.close();
        }
    }

    static RepositoryAccess getResult() {
        if (result == null) {
            assumeTrue(ProcessRunner.isAvailable("srcml"), "srcml not available");
            final Cregit cregit = new Cregit();
            cregit.setLanguage("Java");
            result = TestRepo.rewrite(source,cregit);
        }
        return result;
    }

    // --- Static tests (no srcml required) ---

    @Test
    public void testGuessLanguage() {
        assertEquals("Java", SrcmlAnalyzer.languageOf("Hello.java"));
        assertEquals("C", SrcmlAnalyzer.languageOf("hello.c"));
        assertEquals("C++", SrcmlAnalyzer.languageOf("hello.cpp"));
        assertEquals("C#", SrcmlAnalyzer.languageOf("hello.cs"));

        assertNull(SrcmlAnalyzer.languageOf("hello.py"));
    }

    @Test
    public void testCregitOutputJava() {
        assumeTrue(ProcessRunner.isAvailable("srcml"), "srcml not available");

        assertEquals(String.join("\n",
                "begin_unit|language:Java;cregit-version:0.0.1",
                "begin_class",
                "class|class",
                "name|A",
                "block|{",
                "begin_field",
                "name|int",
                "name|x",
                "decl_stmt|;",
                "end_field",
                "begin_method",
                "name|int",
                "name|get",
                "parameter_list|()",
                "block|{",
                "return|return",
                "name|x",
                "return|;",
                "block|}",
                "end_method",
                "block|}",
                "end_class",
                "end_unit",
                ""
        ), convert("class A { int x; int get() { return x; } }", "Java"));
    }

    @Test
    public void testCregitOutputC() {
        assumeTrue(ProcessRunner.isAvailable("srcml"), "srcml not available");

        assertEquals(String.join("\n",
                "begin_unit|language:C;cregit-version:0.0.1",
                "begin_method",
                "name|int",
                "name|add",
                "parameter_list|(",
                "name|int",
                "name|a",
                "parameter_list|,",
                "name|int",
                "name|b",
                "parameter_list|)",
                "block|{",
                "return|return",
                "name|a",
                "operator|+",
                "name|b",
                "return|;",
                "block|}",
                "end_method",
                "end_unit",
                ""
        ), convert("int add(int a, int b) { return a + b; }", "C"));
    }

    private String convert(String source, String language) {
        return convert(source, language, false);
    }

    private String convert(String source, String language, boolean position) {
        final Cregit cregit = new Cregit().backends(Cregit.BackendType.srcml);
        cregit.position = position;
        cregit.setLanguage(language);
        final String name = switch (language) {
            case "Java" -> "A.java";
            case "C" -> "a.c";
            case "C++" -> "a.cpp";
            case "C#" -> "a.cs";
            default -> "a";
        };
        final AnyHotEntry result = cregit.rewriteBlobEntry(HotEntry.ofBlob(name, source), Context.init());
        return new String(((BlobEntry) result.stream().findFirst().orElseThrow()).getBlob());
    }

    @Test
    public void testCregitOutputWithPosition() {
        assumeTrue(ProcessRunner.isAvailable("srcml"), "srcml not available");

        final String result = convert("int add(int a, int b) { return a + b; }", "C", true);
        // Each line should start with line:col|
        for (String line : result.split("\n")) {
            assertTrue(line.matches("^\\d+:\\d+\\|.+|^-:-\\|.+"),
                    "Expected position prefix in: " + line);
        }
        assertTrue(result.contains("-:-|begin_unit|"));
        assertTrue(result.contains("1:1|name|int"));
        assertTrue(result.contains("-:-|end_method"));
    }

    // --- Integration tests (srcml required) ---

    @Test
    public void testCommitCount() {
        assertEquals(3, getResult().collectCommits("refs/heads/main").size());
    }

    @Test
    public void testCregitFormat() {
        final RevCommit latest = getResult().getHead("refs/heads/main");

        final List<Entry> files = getResult().flattenTree(latest.getTree().getId());

        // Hello.java should be converted to cregit format
        final Entry hello = files.stream()
                .filter(e -> e.getName().equals("Hello.java"))
                .findFirst().orElseThrow();
        final String content = new String(getResult().readBlob(hello.getId()), StandardCharsets.UTF_8);

        assertTrue(content.startsWith("begin_unit"));
        assertTrue(content.contains("end_unit"));
        assertTrue(content.contains("begin_class"));
        assertTrue(content.contains("specifier|public"));
        assertTrue(content.contains("name|Hello"));
    }

    @Test
    public void testNonJavaFileUnchanged() {
        final RevCommit latest = getResult().getHead("refs/heads/main");

        // README.md should have same blob id as source
        final Entry targetReadme = getResult().flattenTree(latest.getTree().getId()).stream()
                .filter(e -> e.getName().equals("README.md"))
                .findFirst().orElseThrow();

        final RevCommit sourceHead = source.getHead("refs/heads/main");
        final Entry sourceReadme = source.flattenTree(sourceHead.getTree().getId()).stream()
                .filter(e -> e.getName().equals("README.md"))
                .findFirst().orElseThrow();

        assertEquals(sourceReadme.getId(), targetReadme.getId());
    }

    @Test
    public void testAllCommitsConverted() {
        for (RevCommit commit : getResult().collectCommits("refs/heads/main")) {
            final List<Entry> files = getResult().flattenTree(commit.getTree().getId());
            final Entry hello = files.stream()
                    .filter(e -> e.getName().equals("Hello.java"))
                    .findFirst().orElseThrow();
            final String content = new String(getResult().readBlob(hello.getId()), StandardCharsets.UTF_8);
            assertTrue(content.startsWith("begin_unit"),
                    "Expected cregit format in commit: " + commit.getFullMessage());
        }
    }

}
