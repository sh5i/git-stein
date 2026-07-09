package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterHtml;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based reimplementation of the imperative Html visitor: HTML is markup with no class/method/field
 * structure, so the query is empty and no elements are extracted; the analyzer exists so that whole-file
 * tokenization ({@code @cregit-ts}) covers HTML too.
 */
public class HtmlAnalyzer extends QueryAnalyzer {
    public HtmlAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterHtml();
    }

    @Override
    protected String queryString() {
        return "";
    }

    @Override
    protected Signature signature(final Element.Kind kind, final TSNode node, final Captures captures) {
        return null; // the empty query captures nothing, so this is never called
    }
}
