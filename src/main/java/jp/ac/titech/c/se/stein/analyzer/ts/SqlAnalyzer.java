package jp.ac.titech.c.se.stein.analyzer.ts;

import java.util.List;

import jp.ac.titech.c.se.stein.analyzer.*;

import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TreeSitterSql;

import jp.ac.titech.c.se.stein.core.SourceEncoding;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;

/**
 * A query-based reimplementation of the imperative Sql visitor: {@code CREATE TABLE} and {@code CREATE VIEW}
 * become classes and {@code CREATE FUNCTION} a method. Each name comes from the statement's
 * {@code object_reference} child. The grammar has no {@code create_procedure} node (the visitor's case
 * for it is dead), so the query omits it.
 */
public class SqlAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (create_table (object_reference name: (_) @name)) @class
            (create_view (object_reference name: (_) @name)) @class
            (create_function (object_reference name: (_) @name)) @method
            """;

    public SqlAnalyzer() {
        super("SQL", new NameFilter(true, "*.sql"), SourceEncoding::decode, TreeSitterSql::new, QUERY);
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
            final String base = flatten(textOf(captures.get("name")));
            return kind == Element.Kind.METHOD ? new Signature(base, null, List.of()) : Signature.of(base);
        }
    }
}
