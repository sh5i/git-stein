package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes an HTML file. HTML is markup with no class/method/field structure, so no elements are
 * extracted; it exists so that whole-file tokenization ({@code @cregit-ts}) covers HTML too.
 */
public class HtmlAnalyzer extends LanguageAnalyzer {
    public HtmlAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected void run() {
        // markup has no class/method/field to extract; only the token stream is meaningful
    }
}
