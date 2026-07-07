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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HistorageTreeSitterTest {
    private final Context c = Context.init();
    private final HistorageTreeSitter app = new HistorageTreeSitter();

    private static final String SOURCE = """
            import os

            VERSION = "1.0"
            a, b = 0, 1

            if os.name == "posix":
                DEBUG = False

            # あいさつ
            def top(a, b=1, *args, **kw):
                def inner():
                    pass
                return a

            @deco
            def decorated(x: int) -> int:
                return x

            def dup():
                pass

            def dup():
                pass

            class Greeter:
                \"""doc\"""

                count: int = 0

                def __init__(self, name: str = "α"):
                    self.name = name

                @staticmethod
                def greet():
                    pass

                class Inner:
                    def m(self):
                        pass

            async def amain():
                pass
            """;

    private Map<String, String> rewrite(final String name, final String source) {
        final AnyHotEntry result = app.rewriteBlobEntry(HotEntry.ofBlob(name, source), c);
        return result.stream().collect(Collectors.toMap(HotEntry::getName, e -> new String(((BlobEntry) e).getBlob())));
    }

    @Test
    public void testModuleNames() {
        final Map<String, String> entries = rewrite("sample.py", SOURCE);
        assertEquals(Set.of(
                "sample.py",  // original
                // a plain single-name assignment at the file top level is a field;
                // tuple assignments and assignments under an if are not
                "sample!VERSION.fpy",
                "sample!top(a,b,+args,++kw).mpy",
                "sample!decorated(x).mpy",
                "sample!dup().mpy",
                "sample!dup()@2.mpy",
                "sample!Greeter.cpy",
                "sample!Greeter#count.fpy",
                "sample!Greeter#__init__(self,name).mpy",
                "sample!Greeter#greet().mpy",
                "sample!Greeter.Inner.cpy",
                "sample!Greeter.Inner#m(self).mpy",
                "sample!amain().mpy"), entries.keySet());
    }

    @Test
    public void testModuleContents() {
        final Map<String, String> entries = rewrite("sample.py", SOURCE);

        // a field is the whole assignment statement
        assertEquals("VERSION = \"1.0\"\n", entries.get("sample!VERSION.fpy"));

        // a top-level function includes its nested function; the nested one is not extracted
        assertEquals("""
                def top(a, b=1, *args, **kw):
                    def inner():
                        pass
                    return a
                """, entries.get("sample!top(a,b,+args,++kw).mpy"));

        // a decorated function includes its decorators
        assertEquals("""
                @deco
                def decorated(x: int) -> int:
                    return x
                """, entries.get("sample!decorated(x).mpy"));

        // a method keeps its indentation; non-ASCII text does not shift the extent
        assertEquals("""
                    def __init__(self, name: str = "α"):
                        self.name = name
                """, entries.get("sample!Greeter#__init__(self,name).mpy"));

        assertEquals("""
                        def m(self):
                            pass
                """, entries.get("sample!Greeter.Inner#m(self).mpy"));
    }

    @Test
    public void testSyntaxErrorToleration() {
        // Python 2 print statement does not parse, but the following def still does
        final Map<String, String> entries = rewrite("p2.py", """
                print "hello"

                def ok():
                    pass
                """);
        assertTrue(entries.containsKey("p2.py"));
        assertTrue(entries.containsKey("p2!ok().mpy"), entries.keySet().toString());
    }

    @Test
    public void testNonPythonBlobIsUntouched() {
        final BlobEntry in = HotEntry.ofBlob("README.md", "# hi\n");
        assertSame(in, app.rewriteBlobEntry(in, c));
    }

    @Test
    public void testTrailingComments() {
        final Map<String, String> entries = rewrite("t.py", """
                def f():
                    return 1  # same-line comment: kept
                    # trailing comment: not part of the definition

                # top-level comment
                def g(x,  # a comment between parameters never joins the signature
                      y):
                    pass
                """);
        assertEquals("""
                def f():
                    return 1  # same-line comment: kept
                """, entries.get("t!f().mpy"));
        assertEquals("""
                def g(x,  # a comment between parameters never joins the signature
                      y):
                    pass
                """, entries.get("t!g(x,y).mpy"));
    }

    @Test
    public void testCodingDeclaration() {
        // a PEP 263 declaration overrides the default UTF-8 (0xE9 = é in latin-1)
        final byte[] latin1 = ("# -*- coding: latin-1 -*-\ndef f():\n    return \"café\"\n")
                .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        final Map<String, String> entries = rewrite2("l.py", latin1);
        assertTrue(entries.get("l!f().mpy").contains("café"), entries.get("l!f().mpy"));
    }

    @Test
    public void testLongNameIsTruncatedWithDigest() {
        final String longName = "f".repeat(300);
        final Map<String, String> entries = rewrite("g.py", "def " + longName + "():\n    pass\n");
        final String filename = entries.keySet().stream().filter(n -> n.endsWith(".mpy")).findFirst().orElseThrow();
        assertTrue(filename.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255, filename);
        assertTrue(filename.contains("~"), filename);  // digest keeps truncated names distinguishable
    }

    private Map<String, String> rewrite2(final String name, final byte[] blob) {
        final AnyHotEntry result = app.rewriteBlobEntry(HotEntry.ofBlob(name, blob), c);
        return result.stream().collect(Collectors.toMap(HotEntry::getName, e -> new String(((BlobEntry) e).getBlob())));
    }

    // --- Java ---

    private static final String JAVA_SOURCE = """
            package example;

            /**
             * A javadoc.
             */
            public class Hello<T> {
                private int count = 0, max = 10;
                static final String NAME = "hello";  // trailing comment

                // leading comment
                public Hello(String name) {
                }

                public <U extends T> java.util.Map<String, U> get(int[] a, List<? extends U> l, String... rest) {
                    return null; // not attached
                }

                static {
                    class Local {}
                }

                enum Color {
                    RED, GREEN {
                        void shine() {}
                    };
                    void mix() {}
                }

                interface Greeter {
                    int LIMIT = 10;
                    void greet(Map<String, Object> m);
                }

                class Inner {
                    void run() {}
                }

                record Point(int x, int y) {
                    Point {
                    }
                    double norm() {
                        return Math.sqrt(x * x + y * y);
                    }
                }
            }

            class Another {
                void act(java.util.function.Function<String, Integer> f) {
                    new Runnable() {
                        public void run() {}  // anonymous: not extracted
                    };
                }
            }
            """;

    @Test
    public void testJavaMatchesHistorageJdt() {
        final Map<String, String> ts = rewrite("Hello.java", JAVA_SOURCE);
        final HistorageJdt jdt = new HistorageJdt();
        final AnyHotEntry out = jdt.rewriteBlobEntry(HotEntry.ofBlob("Hello.java", JAVA_SOURCE), c);
        final Map<String, String> expected = out.stream()
                .collect(Collectors.toMap(HotEntry::getName, e -> new String(((BlobEntry) e).getBlob())));
        assertEquals(expected, ts);
    }

    // --- C++ ---

    private static final String CPP_SOURCE = """
            #include <string>
            namespace app {

            int gCounter = 0;

            class Widget {
            public:
                int id;
                Widget(int id) : id(id) {}
                ~Widget() {}
                int getId() const { return id; }
                bool operator==(const Widget& o) const { return id == o.id; }
            private:
                static int count_;
            };

            int Widget::count_ = 0;

            void freeFunc(const std::string& s) {}

            template<typename T>
            T identity(T x) { return x; }

            struct Point { int x, y; };
            enum Color { RED, GREEN };
            }
            """;

    @Test
    public void testCppModuleNames() {
        final Map<String, String> entries = rewrite("sample.cpp", CPP_SOURCE);
        assertEquals(Set.of(
                "sample.cpp",  // original
                // a namespace is a naming scope only, never emitted as a module
                "sample!app#gCounter.fcpp",
                "sample!app.Widget.ccpp",
                "sample!app.Widget#id.fcpp",
                "sample!app.Widget#Widget(int).mcpp",  // constructor
                "sample!app.Widget#~Widget().mcpp",    // destructor
                "sample!app.Widget#getId().mcpp",
                // an operator keeps its symbol; the parameter name is dropped, the type kept
                "sample!app.Widget#operator==(const~Widget&).mcpp",
                "sample!app.Widget#count_.fcpp",       // in-class static declaration
                // an out-of-line definition stays at its lexical scope; :: flattens to .
                "sample!app#Widget.count_.fcpp",
                "sample!app#freeFunc(const~std;;string&).mcpp",
                "sample!app#identity(T).mcpp",         // template function
                "sample!app.Point.ccpp",
                "sample!app.Point#x.fcpp",
                "sample!app.Point#y.fcpp",             // both members of "int x, y;"
                "sample!app.Color.ccpp"), entries.keySet());
    }

    @Test
    public void testCppModuleContents() {
        final Map<String, String> entries = rewrite("sample.cpp", CPP_SOURCE);

        // a member function keeps its indentation
        assertEquals("    int getId() const { return id; }\n", entries.get("sample!app.Widget#getId().mcpp"));

        // a namespace-scope variable is the whole declaration
        assertEquals("int gCounter = 0;\n", entries.get("sample!app#gCounter.fcpp"));

        // a class module is the whole declaration
        assertEquals("struct Point { int x, y; };\n", entries.get("sample!app.Point.ccpp"));
    }

    @Test
    public void testCppSyntaxErrorToleration() {
        // an unparseable line elsewhere does not stop extraction of the valid function
        final Map<String, String> entries = rewrite("e.cpp", """
                int @@@ broken;

                int ok() { return 1; }
                """);
        assertTrue(entries.containsKey("e.cpp"));
        assertTrue(entries.containsKey("e!ok().mcpp"), entries.keySet().toString());
    }

    // --- C# ---

    private static final String CSHARP_SOURCE = """
            using System;
            namespace App.Core {
              public class Widget<T> : Base {
                private int id;
                public int a, b;
                public string Name { get; set; }
                public const double PI = 3.14;
                public Widget(int id) { this.id = id; }
                ~Widget() {}
                public int GetId() => id;
                public T Cast<U>(U x) where U : class { return default; }
                public static bool operator ==(Widget<T> a, Widget<T> b) => true;
                public event EventHandler Changed;
                enum Color { Red, Green }
                struct Point { public int X, Y; }
              }
              public interface IThing { void Do(int x); }
              public record Person(string Name, int Age);
              public static class Util { public static int Add(int a, int b) => a + b; }
            }
            """;

    @Test
    public void testCsharpModuleNames() {
        final Map<String, String> entries = rewrite("Widget.cs", CSHARP_SOURCE);
        assertEquals(Set.of(
                "Widget.cs",  // original
                // the namespace is a naming scope only; a qualified name stays one segment
                "Widget!App.Core.Widget.ccs",
                "Widget!App.Core.Widget#id.fcs",
                "Widget!App.Core.Widget#a.fcs",
                "Widget!App.Core.Widget#b.fcs",       // both members of "int a, b;"
                "Widget!App.Core.Widget#Name.fcs",    // a property is a field
                "Widget!App.Core.Widget#PI.fcs",
                "Widget!App.Core.Widget#Widget(int).mcs",  // constructor
                "Widget!App.Core.Widget#~Widget().mcs",    // destructor
                "Widget!App.Core.Widget#GetId().mcs",
                "Widget!App.Core.Widget#[U]_Cast(U).mcs",  // generic method, FinerGit-style
                // an operator keeps its symbol; generic angle brackets flatten to be portable
                "Widget!App.Core.Widget#operator==(Widget[T],Widget[T]).mcs",
                "Widget!App.Core.Widget#Changed.fcs",      // an event is a field
                "Widget!App.Core.Widget.Color.ccs",        // nested enum
                "Widget!App.Core.Widget.Point.ccs",        // nested struct
                "Widget!App.Core.Widget.Point#X.fcs",
                "Widget!App.Core.Widget.Point#Y.fcs",
                "Widget!App.Core.IThing.ccs",
                "Widget!App.Core.IThing#Do(int).mcs",
                "Widget!App.Core.Person.ccs",              // record; positional members not split out
                "Widget!App.Core.Util.ccs",
                "Widget!App.Core.Util#Add(int,int).mcs"), entries.keySet());
    }

    @Test
    public void testCsharpModuleContents() {
        final Map<String, String> entries = rewrite("Widget.cs", CSHARP_SOURCE);

        // an expression-bodied method keeps its indentation
        assertEquals("    public int GetId() => id;\n", entries.get("Widget!App.Core.Widget#GetId().mcs"));

        // a property is captured whole
        assertEquals("    public string Name { get; set; }\n", entries.get("Widget!App.Core.Widget#Name.fcs"));
    }

    @Test
    public void testCsharpFileScopedNamespace() {
        // a file-scoped namespace scopes the declarations that follow it
        final Map<String, String> entries = rewrite("F.cs", """
                namespace App;

                public class W {
                    public int GetId() => 1;
                }
                """);
        assertEquals(Set.of(
                "F.cs",
                "F!App.W.ccs",
                "F!App.W#GetId().mcs"), entries.keySet());
    }

    @Test
    public void testCsharpBomFile() {
        // a UTF-8 BOM must not shift the byte-offset-based name extraction (it is stripped)
        final byte[] blob = ("\uFEFFnamespace N { class C { void M() {} } }\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        final Map<String, String> entries = rewrite2("B.cs", blob);
        assertEquals(Set.of(
                "B.cs",
                "B!N.C.ccs",
                "B!N.C#M().mcs"), entries.keySet());
    }

    // --- JavaScript ---

    private static final String JS_SOURCE = """
            import x from 'y';
            const PI = 3.14;
            let counter = 0;
            function greet(name, greeting = 'hi') { return greeting; }
            const add = (a, b) => a + b;
            const one = x => x;
            const f = function(z) {};
            const obj = { m() {} };
            class Widget extends Base {
              static count = 0;
              #secret = 1;
              id = 0;
              constructor(id) { this.id = id; }
              getId() { return this.id; }
              get name() { return 'w'; }
              set name(v) {}
              static create() {}
              *gen() {}
              async fetch(...args) {}
            }
            export function exported() {}
            export const helper = (q) => q;
            export default class Def {}
            """;

    @Test
    public void testJsModuleNames() {
        final Map<String, String> entries = rewrite("app.js", JS_SOURCE);
        assertEquals(Set.of(
                "app.js",  // original
                // non-function top-level bindings are fields; a function-valued one is a method
                "app!PI.fjs",
                "app!counter.fjs",
                "app!greet(name,greeting).mjs",     // default value dropped
                "app!add(a,b).mjs",                 // arrow bound to a variable
                "app!one(x).mjs",                   // single unparenthesized arrow parameter
                "app!f(z).mjs",                     // function expression bound to a variable
                "app!obj.fjs",                      // an object literal is a field; its methods are not split out
                "app!Widget.cjs",
                "app!Widget#count.fjs",             // static field
                "app!Widget##secret.fjs",           // private field keeps its # sigil
                "app!Widget#id.fjs",
                "app!Widget#constructor(id).mjs",
                "app!Widget#getId().mjs",
                "app!Widget#name().mjs",            // getter and setter differ by signature
                "app!Widget#name(v).mjs",
                "app!Widget#create().mjs",          // static method
                "app!Widget#gen().mjs",             // generator method
                "app!Widget#fetch(...args).mjs",    // async method with a rest parameter
                "app!exported().mjs",
                "app!helper(q).mjs",                // exported arrow binding
                "app!Def.cjs"), entries.keySet());
    }

    @Test
    public void testJsModuleContents() {
        final Map<String, String> entries = rewrite("app.js", JS_SOURCE);

        // an arrow bound to a variable is the whole binding statement
        assertEquals("const add = (a, b) => a + b;\n", entries.get("app!add(a,b).mjs"));

        // a plain binding is a field
        assertEquals("const PI = 3.14;\n", entries.get("app!PI.fjs"));
    }

    @Test
    public void testJsSyntaxErrorToleration() {
        // an unparseable line does not stop extraction of the valid function
        final Map<String, String> entries = rewrite("e.js", """
                const @@@ = ;

                function ok() { return 1; }
                """);
        assertTrue(entries.containsKey("e.js"));
        assertTrue(entries.containsKey("e!ok().mjs"), entries.keySet().toString());
    }

    // --- TypeScript ---

    private static final String TS_SOURCE = """
            namespace App {
              export const PI: number = 3.14;
              export function greet(name: string, n = 1): string { return name; }
              const add = (a: number, b: number): number => a + b;
              interface IThing { id: number; do(x: number): void; }
              type Alias = string | number;
              enum Color { Red, Green }
              abstract class Widget<T> extends Base implements IThing {
                private id: number = 0;
                readonly name: string;
                constructor(id: number) { super(); }
                getId(): number { return this.id; }
                do(x: number): void {}
                static create(): Widget<any> { return null; }
                method(a?: string, ...rest: any[]): void {}
              }
            }
            export class Top {}
            """;

    @Test
    public void testTsModuleNames() {
        final Map<String, String> entries = rewrite("App.ts", TS_SOURCE);
        assertEquals(Set.of(
                "App.ts",  // original
                // the namespace is a naming scope; parameter types are dropped, leaving names
                "App!App#PI.fts",
                "App!App#greet(name,n).mts",       // default value dropped
                "App!App#add(a,b).mts",            // arrow bound to a variable
                "App!App#Alias.fts",               // a type alias is a field
                "App!App.Color.cts",               // enum
                "App!App.IThing.cts",              // interface
                "App!App.IThing#id.fts",           // property signature
                "App!App.IThing#do(x).mts",        // method signature
                "App!App.Widget.cts",              // abstract class
                "App!App.Widget#id.fts",
                "App!App.Widget#name.fts",         // a readonly field
                "App!App.Widget#constructor(id).mts",
                "App!App.Widget#getId().mts",
                "App!App.Widget#do(x).mts",
                "App!App.Widget#create().mts",     // static method
                "App!App.Widget#method(a,...rest).mts",  // optional and rest parameters
                "App!Top.cts"), entries.keySet());
    }

    @Test
    public void testTsModuleContents() {
        final Map<String, String> entries = rewrite("App.ts", TS_SOURCE);

        // an enum keeps its (namespace-indented) declaration
        assertEquals("  enum Color { Red, Green }\n", entries.get("App!App.Color.cts"));

        // a member keeps its indentation
        assertEquals("    getId(): number { return this.id; }\n", entries.get("App!App.Widget#getId().mts"));
    }
}
