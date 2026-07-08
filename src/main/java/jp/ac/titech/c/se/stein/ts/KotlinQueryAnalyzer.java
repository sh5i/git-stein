package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterKotlin;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based reimplementation of the imperative Kotlin visitor: detection is the declarative {@link #QUERY}
 * and naming reuses the same child-type lookups. The Kotlin grammar uses few field names, so a
 * declaration is captured only when it carries the name child the visitor looks up, and members that
 * a class exposes through its {@code class_body} nest by containment. An enum's members live in an
 * {@code enum_class_body} the visitor never descends into, so they are pruned in {@link #postProcess}.
 */
public class KotlinQueryAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (class_declaration (type_identifier) @name) @class
            (object_declaration (type_identifier) @name) @class
            (function_declaration (simple_identifier) @name) @method
            (property_declaration (variable_declaration (simple_identifier) @name)) @field
            """;

    public KotlinQueryAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterKotlin();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    @Override
    protected String name(final ElementKind kind, final TSNode node, final Captures captures) {
        if (kind == ElementKind.METHOD) {
            return flatten(textOf(firstChildOfType(node, "simple_identifier"))) + "(" + signature(node) + ")";
        }
        return flatten(textOf(captures.get("name")));
    }

    /**
     * Prunes the members that a query captures inside an enum body: the visitor descends into a class
     * only through its {@code class_body}, so a class whose body is an {@code enum_class_body} (or which
     * has no body at all) exposes no members.
     */
    @Override
    protected void postProcess() {
        prune(root);
    }

    private void prune(final Element element) {
        for (final Element child : element.getChildren()) {
            prune(child);
        }
        if (element.getKind() == ElementKind.CLASS && element.hasContent()
                && firstChildOfType(element.node, "class_body") == null) {
            element.getChildren().clear();
        }
    }

    protected String signature(final TSNode node) {
        final TSNode params = firstChildOfType(node, "function_value_parameters");
        if (params == null) {
            return "";
        }
        final List<String> types = new ArrayList<>();
        for (int i = 0; i < params.getNamedChildCount(); i++) {
            final TSNode p = params.getNamedChild(i);
            if (p.getType().equals("parameter")) {
                final TSNode type = firstChildOfType(p, "user_type");
                types.add(escape(textOf(type != null ? type : p).replaceAll("\\s+", "")));
            }
        }
        return String.join(",", types);
    }
}
