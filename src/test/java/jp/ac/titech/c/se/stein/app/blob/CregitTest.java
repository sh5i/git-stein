package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import jp.ac.titech.c.se.stein.util.ProcessRunner;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class CregitTest {
    static TestRepo source;
    static TestRepo.RewriteResult result;

    @BeforeAll
    static void setUp() throws IOException {
        source = TestRepo.create();
    }

    @AfterAll
    static void tearDown() {
        if (result != null) result.close();
        if (source != null) source.close();
    }

    static TestRepo.RewriteResult getResult() {
        if (result == null) {
            assumeTrue(ProcessRunner.isAvailable("srcml"), "srcml not available");
            final Cregit cregit = new Cregit();
            cregit.setLanguage("Java");
            result = source.rewrite(cregit);
        }
        return result;
    }

    // --- Static tests (no srcml required) ---

    @Test
    public void testGuessLanguage() {
        assertEquals("Java", Cregit.JAVA_FILTER.accept("Hello.java") ? "Java" : null);
        assertEquals("C", Cregit.C_FILTER.accept("hello.c") ? "C" : null);
        assertEquals("C++", Cregit.CXX_FILTER.accept("hello.cpp") ? "C++" : null);
        assertEquals("C#", Cregit.CSHARP_FILTER.accept("hello.cs") ? "C#" : null);

        assertFalse(Cregit.JAVA_FILTER.accept("hello.py"));
        assertFalse(Cregit.C_FILTER.accept("hello.java"));
    }

    @Test
    public void testCregitOutputJava() {
        assumeTrue(ProcessRunner.isAvailable("srcml"), "srcml not available");

        assertEquals(String.join("\n",
                "begin_unit|revision:1.0.0;language:Java;cregit-version:0.0.1",
                "begin_class",
                "class|class",
                "name|A",
                "block|{",
                "name|int",
                "name|x",
                "decl_stmt|;",
                "name|int",
                "name|get",
                "parameter_list|()",
                "block|{",
                "return|return",
                "name|x",
                "return|;",
                "block|}",
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
                "begin_unit|revision:1.0.0;language:C;cregit-version:0.0.1",
                "begin_function",
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
                "end_function",
                "end_unit",
                ""
        ), convert("int add(int a, int b) { return a + b; }", "C"));
    }

    private String convert(String source, String language) {
        final Cregit cregit = new Cregit();
        final byte[] sourceBlob = source.getBytes(StandardCharsets.UTF_8);
        final byte[] resultBlob = cregit.convert(sourceBlob, language, Context.init());
        return new String(resultBlob);
    }

    // --- Integration tests (srcml required) ---

    @Test
    public void testCommitCount() {
        assertEquals(3, getResult().access.collectCommits("refs/heads/main").size());
    }

    @Test
    public void testCregitFormat() {
        final List<RevCommit> commits = getResult().access.collectCommits("refs/heads/main");
        final RevCommit latest = commits.get(commits.size() - 1);

        final List<Entry> files = flattenTree(getResult().access, latest.getTree().getId(), null);

        // Hello.java should be converted to cregit format
        final Entry hello = files.stream()
                .filter(e -> e.getName().equals("Hello.java"))
                .findFirst().orElseThrow();
        final String content = new String(getResult().access.readBlob(hello.getId()), StandardCharsets.UTF_8);

        assertTrue(content.startsWith("begin_unit"));
        assertTrue(content.contains("end_unit"));
        assertTrue(content.contains("begin_class"));
        assertTrue(content.contains("specifier|public"));
        assertTrue(content.contains("name|Hello"));
    }

    @Test
    public void testNonJavaFileUnchanged() {
        final List<RevCommit> commits = getResult().access.collectCommits("refs/heads/main");
        final RevCommit latest = commits.get(commits.size() - 1);

        // README.md should have same blob id as source
        final Entry targetReadme = flattenTree(getResult().access, latest.getTree().getId(), null).stream()
                .filter(e -> e.getName().equals("README.md"))
                .findFirst().orElseThrow();

        final List<RevCommit> sourceCommits = source.access.collectCommits("refs/heads/main");
        final Entry sourceReadme = flattenTree(source.access, sourceCommits.get(sourceCommits.size() - 1).getTree().getId(), null).stream()
                .filter(e -> e.getName().equals("README.md"))
                .findFirst().orElseThrow();

        assertEquals(sourceReadme.getId(), targetReadme.getId());
    }

    @Test
    public void testAllCommitsConverted() {
        for (RevCommit commit : getResult().access.collectCommits("refs/heads/main")) {
            final List<Entry> files = flattenTree(getResult().access, commit.getTree().getId(), null);
            final Entry hello = files.stream()
                    .filter(e -> e.getName().equals("Hello.java"))
                    .findFirst().orElseThrow();
            final String content = new String(getResult().access.readBlob(hello.getId()), StandardCharsets.UTF_8);
            assertTrue(content.startsWith("begin_unit"),
                    "Expected cregit format in commit: " + commit.getFullMessage());
        }
    }

    private static List<Entry> flattenTree(RepositoryAccess ra, ObjectId treeId, String path) {
        final List<Entry> entries = ra.readTree(treeId, path);
        final List<Entry> files = new ArrayList<>();
        for (Entry e : entries) {
            if (e.isTree()) {
                files.addAll(flattenTree(ra, e.getId(), e.getPath()));
            } else {
                files.add(e);
            }
        }
        return files;
    }
}
