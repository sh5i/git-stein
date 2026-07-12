package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;
import org.treesitter.TreeSitterKotlin;

import jp.ac.titech.c.se.stein.core.SourceEncoding;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;

/**
 * A query-based analyzer for Kotlin: detection is the declarative {@link #QUERY}
 * and naming reuses the same child-type lookups. The Kotlin grammar uses few field names, so a
 * declaration is captured only when it carries the expected name child, and members that
 * a class exposes through its {@code class_body} nest by containment. An enum's members live in an
 * {@code enum_class_body} that is never descended into, so they are pruned in {@link #postProcess}.
 */
public class KotlinAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (class_declaration (type_identifier) @name) @class
            (object_declaration (type_identifier) @name) @class
            (function_declaration (simple_identifier) @name) @method
            (property_declaration (variable_declaration (simple_identifier) @name)) @field
            """;

    public KotlinAnalyzer() {
        super("Kotlin", new NameFilter(true, "*.kt", "*.kts"), SourceEncoding::decode, TreeSitterKotlin::new, QUERY);
    }

    @Override
    protected Signature signature(final TreeSitterModel m, final Element.Kind kind, final TSNode node, final Captures captures) {
        if (kind == Element.Kind.METHOD) {
            return new Signature(m.flatten(m.textOf(m.firstChildOfType(node, "simple_identifier"))), null,
                    signature(m, node));
        }
        return Signature.of(m.flatten(m.textOf(captures.get("name"))));
    }

    /**
     * Prunes the members that a query captures inside an enum body: a class exposes members only
     * through its {@code class_body}, so a class whose body is an {@code enum_class_body} (or which
     * has no body at all) exposes no members.
     */
    @Override
    protected void postProcess(final TreeSitterModel m, final Element root) {
        prune(m, root);
    }

    private void prune(final TreeSitterModel m, final Element element) {
        for (final Element child : element.getChildren()) {
            prune(m, child);
        }
        if (element.getKind() == Element.Kind.CLASS && element.hasContent()
                && m.firstChildOfType(m.nodeOf(element), "class_body") == null) {
            element.getChildren().clear();
        }
    }

    protected List<String> signature(final TreeSitterModel m, final TSNode node) {
        final TSNode params = m.firstChildOfType(node, "function_value_parameters");
        if (params == null) {
            return List.of();
        }
        final List<String> types = new ArrayList<>();
        for (int i = 0; i < params.getNamedChildCount(); i++) {
            final TSNode p = params.getNamedChild(i);
            if (p.getType().equals("parameter")) {
                final TSNode type = m.firstChildOfType(p, "user_type");
                types.add(m.escape(m.textOf(type != null ? type : p).replaceAll("\\s+", "")));
            }
        }
        return types;
    }
}
