package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TreeSitterHtml;

import jp.ac.titech.c.se.stein.core.SourceEncoding;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;

/**
 * A query-based reimplementation of the imperative Html visitor: HTML is markup with no class/method/field
 * structure, so the query is empty and no elements are extracted; the analyzer exists so that whole-file
 * tokenization ({@code @cregit-ts}) covers HTML too.
 */
public class HtmlAnalyzer extends QueryAnalyzer {
    public HtmlAnalyzer() {
        super("HTML", new NameFilter(true, "*.html", "*.htm"), SourceEncoding::decode, TreeSitterHtml::new, "");
    }

    @Override
    protected TreeSitterModel createModel(final String filename, final SourceText text, final TSNode treeRoot) {
        return new Model(filename, text, treeRoot, query());
    }

    static class Model extends QueryModel {
        Model(final String filename, final SourceText text, final TSNode treeRoot, final TSQuery query) {
            super(filename, text, treeRoot, query);
        }

        @Override
        protected Signature signature(final Element.Kind kind, final TSNode node, final Captures captures) {
            return null; // the empty query captures nothing, so this is never called
        }
    }
}
