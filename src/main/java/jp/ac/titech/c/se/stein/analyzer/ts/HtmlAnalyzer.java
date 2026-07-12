package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import org.treesitter.TSNode;
import org.treesitter.TreeSitterHtml;

import jp.ac.titech.c.se.stein.core.SourceEncoding;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;

/**
 * A query-based analyzer for HTML: HTML is markup with no class/method/field
 * structure, so the query is empty and no elements are extracted; the analyzer exists so that
 * whole-file tokenization ({@code @cregit}) covers HTML too.
 */
public class HtmlAnalyzer extends QueryAnalyzer {
    public HtmlAnalyzer() {
        super("HTML", new NameFilter(true, "*.html", "*.htm"), SourceEncoding::decode, TreeSitterHtml::new, "");
    }

    @Override
    protected Signature signature(final TreeSitterModel m, final Element.Kind kind, final TSNode node, final Captures captures) {
        return null; // the empty query captures nothing, so this is never called
    }
}
