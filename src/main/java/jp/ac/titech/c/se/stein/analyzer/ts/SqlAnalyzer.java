package jp.ac.titech.c.se.stein.analyzer.ts;

import java.util.List;

import jp.ac.titech.c.se.stein.analyzer.*;

import org.treesitter.TSNode;
import org.treesitter.TreeSitterSql;

import jp.ac.titech.c.se.stein.core.SourceEncoding;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;

/**
 * A query-based analyzer for SQL: {@code CREATE TABLE} and {@code CREATE VIEW}
 * become classes and {@code CREATE FUNCTION} a method. Each name comes from the statement's
 * {@code object_reference} child. The grammar has no {@code create_procedure} node, so the query
 * omits it.
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
    protected Signature signature(final TreeSitterModel m, final Element.Kind kind, final TSNode node, final Captures captures) {
        final String base = m.flatten(m.textOf(captures.get("name")));
        return kind == Element.Kind.METHOD ? new Signature(base, null, List.of()) : Signature.of(base);
    }
}
