package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes an SQL file: {@code CREATE TABLE} becomes a class and {@code CREATE FUNCTION}/
 * {@code CREATE PROCEDURE} a method. SQL has no deeper object structure to split, so the rest is left
 * to whole-file tokenization.
 */
public class SqlAnalyzer extends LanguageAnalyzer {
    public SqlAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected void run() {
        walk(treeRoot, root);
    }

    protected void walk(final TSNode node, final Element parent) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            switch (child.getType()) {
                case "create_table" -> visit(child, parent, ElementKind.CLASS, "");
                case "create_function", "create_procedure" -> visit(child, parent, ElementKind.METHOD, "()");
                default -> {
                    if (!child.isError()) {
                        walk(child, parent);
                    }
                }
            }
        }
    }

    protected void visit(final TSNode node, final Element parent, final ElementKind kind, final String suffix) {
        final TSNode reference = firstChildOfType(node, "object_reference");
        if (reference == null) {
            return;
        }
        final TSNode name = reference.getChildByFieldName("name");
        if (!name.isNull()) {
            element(kind, flatten(textOf(name)) + suffix, parent, node);
        }
    }
}
