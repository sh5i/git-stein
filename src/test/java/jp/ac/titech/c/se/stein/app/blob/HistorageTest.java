package jp.ac.titech.c.se.stein.app.blob;

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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class HistorageTest {
    static TestRepo source;
    static TestRepo.RewriteResult result;

    @BeforeAll
    static void setUp() throws IOException {
        assumeTrue(ProcessRunner.isAvailable("ctags"), "ctags not available");
        source = TestRepo.create();
        result = source.rewrite(new Historage());
    }

    @AfterAll
    static void tearDown() {
        if (result != null) result.close();
        if (source != null) source.close();
    }

    @Test
    public void testCommitCount() {
        assertEquals(3, result.access.collectCommits("refs/heads/main").size());
    }

    @Test
    public void testModuleNames() {
        final List<RevCommit> commits = result.access.collectCommits("refs/heads/main");
        final Set<String> names = collectFileNames(commits.get(commits.size() - 1));

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
        final List<RevCommit> commits = result.access.collectCommits("refs/heads/main");
        final List<Entry> files = collectFiles(commits.get(commits.size() - 1));

        // original Hello.java should have the same blob id as in the source repo
        final Entry orig = files.stream()
                .filter(e -> e.getName().equals("Hello.java"))
                .findFirst().orElseThrow();
        final List<RevCommit> sourceCommits = source.access.collectCommits("refs/heads/main");
        final Entry sourceHello = flattenSourceTree(sourceCommits.get(sourceCommits.size() - 1)).stream()
                .filter(e -> e.getName().equals("Hello.java"))
                .findFirst().orElseThrow();
        assertEquals(sourceHello.getId(), orig.getId());

        // getCount() method module: exact content check
        final Entry getCountModule = files.stream()
                .filter(e -> e.getName().equals("Hello!Hello$getCount(~da39a3).method.java"))
                .findFirst().orElseThrow();
        assertEquals("    public int getCount() {\n        return count;\n    }\n",
                new String(result.access.readBlob(getCountModule.getId())));
    }

    private Set<String> collectFileNames(RevCommit commit) {
        return collectFiles(commit).stream()
                .map(Entry::getName)
                .collect(Collectors.toSet());
    }

    private List<Entry> collectFiles(RevCommit commit) {
        // flatten: root has README.md + com/ tree, but Historage splits blobs,
        // so modules appear alongside the original in the same tree level
        return flattenTree(commit.getTree().getId(), null);
    }

    private List<Entry> flattenTree(ObjectId treeId, String path) {
        return flattenTree(result.access, treeId, path);
    }

    private List<Entry> flattenSourceTree(RevCommit commit) {
        return flattenTree(source.access, commit.getTree().getId(), null);
    }

    private static List<Entry> flattenTree(RepositoryAccess ra, ObjectId treeId, String path) {
        final List<Entry> entries = ra.readTree(treeId, path);
        final List<Entry> files = new java.util.ArrayList<>();
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
