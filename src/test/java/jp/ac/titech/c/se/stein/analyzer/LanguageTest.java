package jp.ac.titech.c.se.stein.analyzer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LanguageTest {
    @Test
    public void testDispatchByExtension() {
        assertEquals(Language.JAVA, Language.of("Hello.java"));
        assertEquals(Language.PYTHON, Language.of("util.py"));
        assertEquals(Language.C, Language.of("main.c"));
        assertEquals(Language.CSHARP, Language.of("App.cs"));
        assertEquals(Language.TYPESCRIPT, Language.of("mod.mts"));  // a real extension, not a module name

        // matching is case-insensitive
        assertEquals(Language.JAVA, Language.of("Hello.JAVA"));

        assertNull(Language.of("README.md"));
        assertNull(Language.of("Makefile"));
    }

    @Test
    public void testSharedHeaderExtension() {
        // .h is claimed by both C++ and C; the declaration order makes C++ answer the dispatch
        assertTrue(Language.CPP.getExtensions().contains(".h"));
        assertTrue(Language.C.getExtensions().contains(".h"));
        assertEquals(Language.CPP, Language.of("vec.h"));
    }

    @Test
    public void testRealExtensionsThatLookLikeModuleNames() {
        // .mjs/.cjs/.mts/.cts are real extensions, not Historage kind prefixes: the direct match wins
        // (and even the fallback would agree, m+js being JavaScript too)
        assertEquals(Language.JAVASCRIPT, Language.of("mod.mjs"));
        assertEquals(Language.JAVASCRIPT, Language.of("mod.cjs"));
        assertEquals(Language.TYPESCRIPT, Language.of("mod.cts"));
    }

    @Test
    public void testHistorageModuleFallback() {
        // an unmatched extension retries with the Historage kind letter stripped, so a module file
        // answers as its language
        assertEquals(Language.JAVA, Language.of("Hello#greet(int).mjava"));
        assertEquals(Language.JAVA, Language.of("Hello.cjava"));
        assertEquals(Language.JAVA, Language.of("Hello#name.fjava"));
        assertEquals(Language.PYTHON, Language.of("util!main().mpy"));
        assertEquals(Language.CPP, Language.of("vec!Vec.ccpp"));
    }

    @Test
    public void testOfName() {
        assertEquals(Language.CPP, Language.ofName("C++"));
        assertEquals(Language.CSHARP, Language.ofName("C#"));
        assertEquals(Language.JAVA, Language.ofName("Java"));
        assertNull(Language.ofName("COBOL"));
    }
}
