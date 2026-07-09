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
    public void testCtagsNaming() {
        final Historage.NamingStrategy.Ctags digest = new Historage.NamingStrategy.Ctags(true, true);
        final Historage.NamingStrategy.Ctags plain = new Historage.NamingStrategy.Ctags(false, true);

        // a name with no signature has no parentheses
        assertEquals("greet", digest.leafName(Signature.of("greet")));

        // signature digested: "(int, String)" normalizes to "int,String", then a 6-char hash
        assertEquals("greet(~f3299f)", digest.leafName(new Signature("greet", null, List.of("int, String"))));

        // signature not digested: comma-adjacent spaces are removed, other spaces become ~
        assertEquals("greet(int)", plain.leafName(new Signature("greet", null, List.of("int"))));
        assertEquals("greet(int,String~name)", plain.leafName(new Signature("greet", null, List.of("int, String name"))));
        assertEquals("getValue(Map[String,Object])",
                plain.leafName(new Signature("getValue", null, List.of("Map<String, Object>"))));

        // the extension is the ctags kind, plus the source extension when requiresOriginalExtension
        assertEquals(".method.java", digest.extension(Element.Kind.METHOD, "method", "Hello.java"));
        assertEquals(".method", new Historage.NamingStrategy.Ctags(true, false).extension(Element.Kind.METHOD, "method", "Hello.java"));
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
                "README!Hello.chapter.md",
                // class/interface/enum
                "Hello!Hello.class.java",
                "Hello!Hello$Color.enum.java",
                "Hello!Hello$Greeter.interface.java",
                "Hello!Hello$Formatter.class.java",
                "Hello!Hello$Rect.interface.java",
                // package
                "Hello!com.example.package.java",
                // fields
                "Hello!Hello$name.field.java",
                "Hello!Hello$count.field.java",
                "Hello!Hello$SEPARATOR.field.java",
                "Hello!Hello$VERSION.field.java",
                "Hello!Hello.Color$rgb.field.java",
                "Hello!Hello.Formatter$prefix.field.java",
                // enum constants
                "Hello!Hello.Color$RED.enumConstant.java",
                "Hello!Hello.Color$GREEN.enumConstant.java",
                "Hello!Hello.Color$BLUE.enumConstant.java",
                // constructors
                "Hello!Hello$Hello(~da39a3).method.java",
                "Hello!Hello$Hello(~523327).method.java",
                "Hello!Hello.Color$Color(~bf7093).method.java",
                "Hello!Hello.Formatter$Formatter(~76a057).method.java",
                // methods
                "Hello!Hello$greet(~da39a3).method.java",
                "Hello!Hello$greet(~ea7b37).method.java",
                "Hello!Hello$greetMany(~f8808a).method.java",
                "Hello!Hello$getCount(~da39a3).method.java",
                "Hello!Hello$stringify(~b00d43).method.java",
                "Hello!Hello$toArray(~9633b1).method.java",
                "Hello!Hello$process(~22cda2).method.java",
                "Hello!Hello$processUpper(~da39a3).method.java",
                "Hello!Hello$isLongName(~da39a3).method.java",
                "Hello!Hello$describeColor(~5621eb).method.java",
                "Hello!Hello$getVersion(~da39a3).method.java",
                "Hello!Hello$Pair(~b7f68c).method.java",
                "Hello!Hello.Greeter$greet(~da39a3).method.java",
                "Hello!Hello.Greeter$greetLoud(~da39a3).method.java",
                "Hello!Hello.Color$toHex(~da39a3).method.java",
                "Hello!Hello.Rect$area(~da39a3).method.java",
                "Hello!Hello.Formatter$format(~187b49).method.java"
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
                .filter(e -> e.getName().equals("Hello!Hello$getCount(~da39a3).method.java"))
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
