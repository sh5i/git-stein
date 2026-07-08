package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterSql;

import jp.ac.titech.c.se.stein.core.SourceText;

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

    public SqlAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterSql();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    @Override
    protected String name(final ElementKind kind, final TSNode node, final Captures captures) {
        final String base = flatten(textOf(captures.get("name")));
        return kind == ElementKind.METHOD ? base + "()" : base;
    }
}
