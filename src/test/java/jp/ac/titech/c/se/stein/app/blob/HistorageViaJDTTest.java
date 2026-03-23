package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.app.blob.HistorageViaJDT.Module;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import org.eclipse.jgit.lib.FileMode;
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

public class HistorageViaJDTTest {

    static String sampleSource;
    static RepositoryAccess source, result;

    @BeforeAll
    static void setUp() throws IOException {
        try (InputStream is = HistorageViaJDTTest.class.getResourceAsStream("/sample/Hello.java.v3")) {
            sampleSource = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        source = TestRepo.createSample();
        result = TestRepo.rewrite(source,new HistorageViaJDT());
    }

    @AfterAll
    static void tearDown() {
        result.close();
        source.close();
    }

    List<Module> generateModules() {
        return generateModules(new HistorageViaJDT());
    }

    List<Module> generateModules(HistorageViaJDT historage) {
        SourceText text = SourceText.ofNormalized(sampleSource.getBytes(StandardCharsets.UTF_8));
        return historage.new ModuleGenerator("Hello.java", text).generate();
    }

    // --- Module generation tests ---

    @Test
    public void testModuleNames() {
        Set<String> filenames = generateModules().stream()
                .map(Module::getFilename).collect(Collectors.toSet());

        assertEquals(Set.of(
                // classes
                "Hello.cjava",
                "Hello.Color.cjava",
                "Hello.Greeter.cjava",
                "Hello.Shape.cjava",
                "Hello.Formatter.cjava",
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
                // records
                "Hello#joined().mjava",
                "Hello#area().mjava"
        ), filenames);
    }

    @Test
    public void testModuleContent() {
        List<Module> modules = generateModules();

        // getCount(): exact content
        Module getCount = modules.stream()
                .filter(m -> m.getFilename().equals("Hello#getCount().mjava"))
                .findFirst().orElseThrow();
        assertEquals("    public int getCount() {\n        return count;\n    }\n",
                new String(getCount.getBlob(), StandardCharsets.UTF_8));

        // class module contains declaration and fields
        Module classModule = modules.stream()
                .filter(m -> m.getFilename().equals("Hello.cjava"))
                .findFirst().orElseThrow();
        String classContent = new String(classModule.getBlob(), StandardCharsets.UTF_8);
        assertTrue(classContent.contains("public class Hello"));
        assertTrue(classContent.contains("private final String name"));
    }

    // --- Option tests ---

    @Test
    public void testExcludeClasses() {
        HistorageViaJDT historage = new HistorageViaJDT();
        historage.requiresClasses = false;
        assertTrue(generateModules(historage).stream()
                .noneMatch(m -> m.getFilename().endsWith(".cjava")));
    }

    @Test
    public void testExcludeMethods() {
        HistorageViaJDT historage = new HistorageViaJDT();
        historage.requiresMethods = false;
        assertTrue(generateModules(historage).stream()
                .noneMatch(m -> m.getFilename().endsWith(".mjava")));
    }

    @Test
    public void testExcludeFields() {
        HistorageViaJDT historage = new HistorageViaJDT();
        historage.requiresFields = false;
        assertTrue(generateModules(historage).stream()
                .noneMatch(m -> m.getFilename().endsWith(".fjava")));
    }

    @Test
    public void testDigestParameters() {
        HistorageViaJDT historage = new HistorageViaJDT();
        historage.digestParameters = true;
        List<Module> modules = generateModules(historage);

        // methods with parameters should have digested names (~XXXXXX)
        Module greetBool = modules.stream()
                .filter(m -> m.getFilename().contains("greet(~") && m.getFilename().endsWith(".mjava"))
                .findFirst().orElseThrow();
        assertTrue(greetBool.getFilename().matches("Hello#greet\\(~[0-9a-f]{6}\\)\\.mjava"));

        // no-arg methods should have empty parens (no digest)
        assertTrue(modules.stream().anyMatch(m -> m.getFilename().equals("Hello#greet().mjava")));
    }

    @Test
    public void testNonJavaFilePassedThrough() {
        BlobEntry entry = HotEntry.ofBlob("README.md", "# Hello");
        HistorageViaJDT historage = new HistorageViaJDT();
        AnyHotEntry result = historage.rewriteBlobEntry(entry, Context.init());
        assertEquals(1, result.size());
        assertSame(entry, result.stream().findFirst().orElseThrow());
    }

    @Test
    public void testRequiresOriginals() {
        HistorageViaJDT historage = new HistorageViaJDT();
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
