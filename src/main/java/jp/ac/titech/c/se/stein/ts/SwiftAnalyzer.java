package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterSwift;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based reimplementation of the imperative Swift visitor: detection is the declarative {@link #QUERY}
 * and naming reuses the same child-type lookups. Classes, structs, enums, and protocols all surface as
 * class-like declarations; an initializer becomes a method named {@code init}. The visitor enters a
 * type only when it has a direct {@code type_identifier} name, so an {@code extension} (whose name is a
 * {@code user_type}) and everything inside it are skipped; {@link #postProcess} drops the members a
 * query would otherwise orphan from such an un-entered type.
 */
public class SwiftAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (class_declaration name: (type_identifier) @name) @class
            (protocol_declaration name: (type_identifier) @name) @class
            (function_declaration name: (simple_identifier) @name) @method
            (protocol_function_declaration name: (simple_identifier) @name) @method
            (init_declaration) @method
            (property_declaration name: (pattern bound_identifier: (simple_identifier) @name)) @field
            """;

    public SwiftAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterSwift();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    @Override
    protected String name(final ElementKind kind, final TSNode node, final Captures captures) {
        if (kind == ElementKind.METHOD) {
            final String leaf = node.getType().equals("init_declaration") ? "init"
                    : flatten(textOf(captures.get("name")));
            return leaf + "(" + signature(node) + ")";
        }
        return flatten(textOf(captures.get("name")));
    }

    /**
     * Drops any element nested inside a type the visitor never entered (a {@code class_declaration} or
     * {@code protocol_declaration} without a direct {@code type_identifier} name, i.e. an extension), so
     * that members a query captured there are not orphaned to the file root.
     */
    @Override
    protected void postProcess() {
        prune(root);
    }

    private void prune(final Element element) {
        element.getChildren().removeIf(c -> c.hasContent() && insideUnenteredType(c.node));
        for (final Element child : element.getChildren()) {
            prune(child);
        }
    }

    private boolean insideUnenteredType(final TSNode node) {
        for (TSNode p = node.getParent(); p != null && !p.isNull(); p = p.getParent()) {
            final String type = p.getType();
            if ((type.equals("class_declaration") || type.equals("protocol_declaration"))
                    && firstChildOfType(p, "type_identifier") == null) {
                return true;
            }
        }
        return false;
    }

    protected String signature(final TSNode node) {
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode p = node.getNamedChild(i);
            if (p.getType().equals("parameter")) {
                final TSNode name = firstChildOfType(p, "simple_identifier");
                names.add(escape(textOf(name != null ? name : p).replaceAll("\\s+", "")));
            }
        }
        return String.join(",", names);
    }
}
