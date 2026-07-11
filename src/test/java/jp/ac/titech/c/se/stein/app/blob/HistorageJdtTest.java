package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

public class HistorageJdtTest {

    static String sampleSource;
    static RepositoryAccess source, result;

    @BeforeAll
    static void setUp() throws IOException {
        try (InputStream is = HistorageJdtTest.class.getResourceAsStream("/sample/Hello.java.v3")) {
            sampleSource = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        source = TestRepo.createSample();
        result = TestRepo.rewrite(source,new Historage().backends(Historage.BackendType.jdt));
    }

    @AfterAll
    static void tearDown() {
        result.close();
        source.close();
    }

    List<BlobEntry> generateModules() {
        return generateModules(jdt(true, true, true, false));
    }

    List<BlobEntry> generateModules(Historage backend) {
        BlobEntry entry = HotEntry.ofBlob("Hello.java", sampleSource);
        return backend.rewriteBlobEntry(entry, Context.init()).stream()
                .map(e -> (BlobEntry) e).toList();
    }

    private Historage jdt(final boolean classes, final boolean methods, final boolean fields, final boolean digestParams) {
        final Historage h = new Historage().backends(Historage.BackendType.jdt);
        h.requiresClasses = classes;
        h.requiresMethods = methods;
        h.requiresFields = fields;
        h.digestParameters = digestParams;
        h.requiresOriginals = false;  // keep only the generated modules for the assertions
        return h;
    }

    private Historage jdtWithSideFiles() {
        final Historage h = new Historage().backends(Historage.BackendType.jdt);
        h.requiresComments = true;
        h.requiresMapping = true;
        h.requiresOriginals = false;
        return h;
    }

    // --- Module generation tests ---

    @Test
    public void testModuleNames() {
        Set<String> filenames = generateModules().stream()
                .map(BlobEntry::getName).collect(Collectors.toSet());

        assertEquals(Set.of(
                // classes (records included)
                "Hello.cjava",
                "Hello.Color.cjava",
                "Hello.Greeter.cjava",
                "Hello.Shape.cjava",
                "Hello.Formatter.cjava",
                "Hello.Pair.cjava",
                "Hello.Circle.cjava",
                "Hello.Rect.cjava",
                // fields
                "Hello#SEPARATOR.fjava",
                "Hello#VERSION.fjava",
                "Hello#name.fjava",
                "Hello#count.fjava",
                "Hello.Color#rgb.fjava",
                "Hello.Formatter#prefix.fjava",
                // constructors
                "Hello#Hello().mjava",
                "Hello#Hello(String).mjava",
                "Hello.Color#Color(int).mjava",
                "Hello.Formatter#Formatter(String).mjava",
                // methods
                "Hello#greet().mjava",
                "Hello#greet(boolean).mjava",
                "Hello#greetMany(int).mjava",
                "Hello#getCount().mjava",
                "Hello#[T]_stringify(T).mjava",
                "Hello#toArray(List[String]).mjava",
                "Hello#process(Function[String,String]).mjava",
                "Hello#processUpper().mjava",
                "Hello#isLongName().mjava",
                "Hello#describeColor(Color).mjava",
                "Hello#getVersion().mjava",
                "Hello.Greeter#greet().mjava",
                "Hello.Greeter#greetLoud().mjava",
                "Hello.Color#toHex().mjava",
                "Hello.Formatter#format(String).mjava",
                "Hello.Shape#area().mjava",
                // record members belong to their record, not the enclosing class
                "Hello.Pair#joined().mjava",
                "Hello.Circle#area().mjava",
                "Hello.Rect#area().mjava"
        ), filenames);
    }

    @Test
    public void testModuleContent() {
        List<BlobEntry> modules = generateModules();

        // getCount(): exact content
        BlobEntry getCount = modules.stream()
                .filter(m -> m.getName().equals("Hello#getCount().mjava"))
                .findFirst().orElseThrow();
        assertEquals("    public int getCount() {\n        return count;\n    }\n",
                new String(getCount.getBlob(), StandardCharsets.UTF_8));

        // class module contains declaration and fields
        BlobEntry classModule = modules.stream()
                .filter(m -> m.getName().equals("Hello.cjava"))
                .findFirst().orElseThrow();
        String classContent = new String(classModule.getBlob(), StandardCharsets.UTF_8);
        assertTrue(classContent.contains("public class Hello"));
        assertTrue(classContent.contains("private final String name"));
    }

    // --- Option tests ---

    @Test
    public void testExcludeClasses() {
        assertTrue(generateModules(jdt(false, true, true, false)).stream()
                .noneMatch(m -> m.getName().endsWith(".cjava")));
    }

    @Test
    public void testExcludeMethods() {
        assertTrue(generateModules(jdt(true, false, true, false)).stream()
                .noneMatch(m -> m.getName().endsWith(".mjava")));
    }

    @Test
    public void testExcludeFields() {
        assertTrue(generateModules(jdt(true, true, false, false)).stream()
                .noneMatch(m -> m.getName().endsWith(".fjava")));
    }

    @Test
    public void testDigestParameters() {
        List<BlobEntry> modules = generateModules(jdt(true, true, true, true));

        // methods with parameters should have digested names (~XXXXXX)
        BlobEntry greetBool = modules.stream()
                .filter(m -> m.getName().contains("greet(~") && m.getName().endsWith(".mjava"))
                .findFirst().orElseThrow();
        assertTrue(greetBool.getName().matches("Hello#greet\\(~[0-9a-f]{6}\\)\\.mjava"));

        // no-arg methods should have empty parens (no digest)
        assertTrue(modules.stream().anyMatch(m -> m.getName().equals("Hello#greet().mjava")));
    }

    @Test
    public void testCommentAndMappingSideFiles() {
        List<BlobEntry> modules = generateModules(jdtWithSideFiles());

        // the class's comment file carries its Javadoc
        BlobEntry classComment = modules.stream()
                .filter(m -> m.getName().equals("Hello.cjava.com"))
                .findFirst().orElseThrow();
        assertTrue(new String(classComment.getBlob(), StandardCharsets.UTF_8)
                .contains("A sample class for testing blob converters"));

        // exactly one mapping file, in JSON-lines form recording line ranges
        List<BlobEntry> mappings = modules.stream()
                .filter(m -> m.getName().endsWith(".mapping")).toList();
        assertEquals(1, mappings.size());
        String mapping = new String(mappings.get(0).getBlob(), StandardCharsets.UTF_8);
        assertTrue(mapping.contains("\"filename\""));
        assertTrue(mapping.contains("\"beginLine\""));
    }

    @Test
    public void testNonJavaFilePassedThrough() {
        BlobEntry entry = HotEntry.ofBlob("README.md", "# Hello");
        Historage historage = new Historage().backends(Historage.BackendType.jdt);
        AnyHotEntry result = historage.rewriteBlobEntry(entry, Context.init());
        assertEquals(1, result.size());
        assertSame(entry, result.stream().findFirst().orElseThrow());
    }

    @Test
    public void testSyntaxErrorToleration() {
        // JDT's error recovery still yields the valid method's module despite a broken declaration
        BlobEntry entry = HotEntry.ofBlob("Broken.java", """
                public class Broken {
                    int @@@ broken;

                    int ok() { return 1; }
                }
                """);
        Set<String> names = new Historage().backends(Historage.BackendType.jdt)
                .rewriteBlobEntry(entry, Context.init()).stream()
                .map(HotEntry::getName).collect(Collectors.toSet());
        assertTrue(names.contains("Broken#ok().mjava"), names.toString());
    }

    @Test
    public void testRequiresOriginals() {
        Historage historage = new Historage().backends(Historage.BackendType.jdt);
        historage.requiresOriginals = false;
        BlobEntry entry = HotEntry.ofBlob("Hello.java", sampleSource);
        AnyHotEntry result = historage.rewriteBlobEntry(entry, Context.init());

        // original should NOT be included
        assertTrue(result.stream().noneMatch(e -> e.getName().equals("Hello.java")));
        // but modules should still be generated
        assertTrue(result.size() > 0);
    }

    // --- TestRepo integration tests ---

    @Test
    public void testRewriteCommitCount() {
        assertEquals(3, result.collectCommits("refs/heads/main").size());
    }

    @Test
    public void testRewriteProducesModules() {
        for (RevCommit commit : result.collectCommits("refs/heads/main")) {
            // navigate to com/example/ where Hello.java and its modules live
            final List<Entry> root =
                    result.readTree(commit.getTree().getId(), null);
            final Entry com = root.stream()
                    .filter(e -> e.getName().equals("com")).findFirst().orElseThrow();
            final Entry example =
                    result.readTree(com.getId(), null).get(0);
            final List<Entry> exampleEntries =
                    result.readTree(example.getId(), null);

            // should have Hello.java (original) + generated modules
            assertTrue(exampleEntries.size() > 1,
                    "Expected modules in commit: " + commit.getFullMessage() + ", got: " +
                    exampleEntries.stream().map(Entry::getName).collect(Collectors.toList()));
            assertTrue(exampleEntries.stream().anyMatch(e -> e.getName().equals("Hello.java")));
        }
    }
}
