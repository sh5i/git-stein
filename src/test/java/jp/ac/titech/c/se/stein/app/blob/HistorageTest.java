package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.analyzer.Element;
import jp.ac.titech.c.se.stein.analyzer.Signature;
import jp.ac.titech.c.se.stein.entry.Entry;
import jp.ac.titech.c.se.stein.core.RepositoryAccess;
import jp.ac.titech.c.se.stein.testing.TestRepo;
import jp.ac.titech.c.se.stein.util.ProcessRunner;
import org.eclipse.jgit.revwalk.RevCommit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class HistorageTest {
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
            assumeTrue(ProcessRunner.isAvailable("ctags"), "ctags not available");
            result = TestRepo.rewrite(source,new Historage().backends(Historage.BackendType.ctags));
        }
        return result;
    }

    // --- Static tests (no ctags required) ---

    @Test
    public void testNaming() {
        final Historage.NamingStrategy plain = new Historage.NamingStrategy(false, false);
        final Historage.NamingStrategy digest = new Historage.NamingStrategy(false, true);
        final Historage.NamingStrategy unqualify = new Historage.NamingStrategy(true, false);

        // a name with no signature has no parentheses; an empty parameter list keeps them
        assertEquals("greet", plain.leafName(Signature.of("greet")));
        assertEquals("greet()", plain.leafName(new Signature("greet", null, List.of())));

        // parameters are joined with commas; digesting turns the non-empty list into a 6-char hash
        assertEquals("greet(int,String)", plain.leafName(new Signature("greet", null, List.of("int", "String"))));
        assertEquals("greet(~f3299f)", digest.leafName(new Signature("greet", null, List.of("int", "String"))));
        assertEquals("greet()", digest.leafName(new Signature("greet", null, List.of())));

        // unqualifying strips the package qualification from each type name
        assertEquals("f(String)", unqualify.leafName(new Signature("f", null, List.of("java.lang.String"))));

        // the extension is a kind marker plus the source extension: a single letter for a neutral
        // kind, the raw kind for a RAW element, and no extension part for an extensionless file
        assertEquals(".mjava", plain.extension(Element.Kind.METHOD, "method", "Hello.java"));
        assertEquals(".chaptermd", plain.extension(Element.Kind.RAW, "chapter", "README.md"));
        assertEquals(".target", plain.extension(Element.Kind.RAW, "target", "Makefile"));
    }

    // --- Integration tests (ctags required) ---

    @Test
    public void testCommitCount() {
        assertEquals(3, getResult().collectCommits("refs/heads/main").size());
    }

    @Test
    public void testModuleNames() {
        final RevCommit head = getResult().getHead("refs/heads/main");
        final Set<String> names = collectFileNames(head);

        assertEquals(Set.of(
                // originals
                "Hello.java",
                "README.md",
                // a Markdown chapter has no neutral kind: RAW, so its ctags kind is the marker
                "README!Hello.chaptermd",
                // class/interface/enum; the top-level class matches the file base, so the "Hello!"
                // prefix is elided (FinerGit's Java convention, generalized)
                "Hello.cjava",
                "Hello.Color.cjava",
                "Hello.Greeter.cjava",
                "Hello.Formatter.cjava",
                "Hello.Rect.cjava",
                // package
                "Hello!com.example.cjava",
                // fields
                "Hello#name.fjava",
                "Hello#count.fjava",
                "Hello#SEPARATOR.fjava",
                "Hello#VERSION.fjava",
                "Hello!Hello.Color#rgb.fjava",
                "Hello!Hello.Formatter#prefix.fjava",
                // enum constants are RAW too
                "Hello!Hello.Color#RED.enumConstantjava",
                "Hello!Hello.Color#GREEN.enumConstantjava",
                "Hello!Hello.Color#BLUE.enumConstantjava",
                // constructors; signatures are spelled out escaped (digesting is opt-in)
                "Hello#Hello().mjava",
                "Hello#Hello(String~name).mjava",
                "Hello!Hello.Color#Color(int~rgb).mjava",
                "Hello!Hello.Formatter#Formatter(String~prefix).mjava",
                // methods
                "Hello#greet().mjava",
                "Hello#greet(boolean~formal).mjava",
                "Hello#greetMany(int~times).mjava",
                "Hello#getCount().mjava",
                "Hello#stringify(T~value).mjava",
                "Hello#toArray(List[String]~list).mjava",
                "Hello#process(Function[String,String]~transform).mjava",
                "Hello#processUpper().mjava",
                "Hello#isLongName().mjava",
                "Hello#describeColor(Color~color).mjava",
                "Hello#getVersion().mjava",
                "Hello#Pair(String~first,String~second).mjava",
                "Hello!Hello.Greeter#greet().mjava",
                "Hello!Hello.Greeter#greetLoud().mjava",
                "Hello!Hello.Color#toHex().mjava",
                "Hello!Hello.Rect#area().mjava",
                "Hello!Hello.Formatter#format(String~text).mjava"
        ), names);
    }

    @Test
    public void testModuleContent() {
        final RevCommit head = getResult().getHead("refs/heads/main");
        final List<Entry> files = collectFiles(head);

        // original Hello.java should have the same blob id as in the source repo
        final Entry orig = files.stream()
                .filter(e -> e.getName().equals("Hello.java"))
                .findFirst().orElseThrow();
        final RevCommit sourceHead = source.getHead("refs/heads/main");
        final Entry sourceHello = source.flattenTree(sourceHead.getTree().getId()).stream()
                .filter(e -> e.getName().equals("Hello.java"))
                .findFirst().orElseThrow();
        assertEquals(sourceHello.getId(), orig.getId());

        // getCount() method module: exact content check
        final Entry getCountModule = files.stream()
                .filter(e -> e.getName().equals("Hello#getCount().mjava"))
                .findFirst().orElseThrow();
        assertEquals("    public int getCount() {\n        return count;\n    }\n",
                new String(getResult().readBlob(getCountModule.getId())));
    }

    // --- Helpers ---

    private Set<String> collectFileNames(RevCommit commit) {
        return collectFiles(commit).stream()
                .map(Entry::getName)
                .collect(Collectors.toSet());
    }

    private List<Entry> collectFiles(RevCommit commit) {
        return getResult().flattenTree(commit.getTree().getId());
    }
}
