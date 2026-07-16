package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.analyzer.TreeSitterAnalyzer;
import jp.ac.titech.c.se.stein.analyzer.Token;
import jp.ac.titech.c.se.stein.analyzer.TokenizingModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Every tree-sitter language must flag its comment tokens, so that {@code @historage --tokens} skips
 * them. Most grammars mark a comment as an extra node, but some (Rust, Dart) model it as a named node
 * whose punctuation leaves are not extra; a comment-only source must still tokenize entirely as
 * comments.
 */
public class CommentClassificationTest {
    private record Case(String file, String source) {}

    private static final List<Case> CASES = List.of(
            new Case("a.py", "# comment\n"),
            new Case("a.java", "// comment\n/* block */\n"),
            new Case("a.cpp", "// comment\n/* block */\n"),
            new Case("a.cs", "// comment\n"),
            new Case("a.js", "// comment\n"),
            new Case("a.ts", "// comment\n"),
            new Case("a.c", "/* comment */\n"),
            new Case("a.go", "// comment\n"),
            new Case("a.kt", "// comment\n"),
            new Case("a.rs", "// comment\n/// doc\n"),
            new Case("a.swift", "// comment\n/* block */\n"),
            new Case("a.rb", "# comment\n"),
            new Case("a.php", "<?php // comment\n# hash\n"),
            new Case("a.dart", "// comment\n/// doc\n"),
            new Case("a.m", "// comment\n"),
            new Case("a.r", "# comment\n"),
            new Case("a.sh", "# comment\n"),
            new Case("a.sql", "-- comment\n"),
            new Case("a.html", "<!-- comment -->\n"));

    @Test
    public void testCommentTokensAreFlaggedInEveryLanguage() {
        final List<String> leaks = new ArrayList<>();
        for (final Case c : CASES) {
            final TokenizingModel a = new TreeSitterAnalyzer().extract(c.file, c.source.getBytes(), null);
            assertNotNull(a, c.file);
            final List<Token> tokens = a.getTokens(a.getRoot());
            for (final Token t : tokens) {
                // the PHP open tag is the one non-comment token in these comment-only sources
                if (!t.comment() && !t.type().equals("PHP_TAG")) {
                    leaks.add(c.file + ": " + t.text() + " (" + t.type() + ")");
                }
            }
        }
        assertEquals(List.of(), leaks);
    }
}
