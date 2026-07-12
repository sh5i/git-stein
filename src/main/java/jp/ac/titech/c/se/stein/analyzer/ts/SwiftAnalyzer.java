package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;
import org.treesitter.TreeSitterSwift;


/**
 * A query-based analyzer for Swift: detection is the declarative {@link #QUERY}
 * and naming reuses the same child-type lookups. Classes, structs, enums, and protocols all surface as
 * class-like declarations; an initializer becomes a method named {@code init}. A type is entered
 * only when it has a direct {@code type_identifier} name, so an {@code extension} (whose name is a
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

    public SwiftAnalyzer() {
        super(Language.SWIFT, TreeSitterSwift::new, QUERY);
    }

    @Override
    protected Signature signature(final TreeSitterModel m, final Element.Kind kind, final TSNode node, final Captures captures) {
        if (kind == Element.Kind.METHOD) {
            final String leaf = node.getType().equals("init_declaration") ? "init"
                    : m.flatten(m.textOf(captures.get("name")));
            return new Signature(leaf, null, signature(m, node));
        }
        return Signature.of(m.flatten(m.textOf(captures.get("name"))));
    }

    /**
     * Drops any element nested inside a type that was never entered (a {@code class_declaration} or
     * {@code protocol_declaration} without a direct {@code type_identifier} name, i.e. an extension), so
     * that members a query captured there are not orphaned to the file root.
     */
    @Override
    protected void postProcess(final TreeSitterModel m, final Element root) {
        prune(m, root);
    }

    private void prune(final TreeSitterModel m, final Element element) {
        element.getChildren().removeIf(c -> c.hasContent() && insideUnenteredType(m, m.nodeOf(c)));
        for (final Element child : element.getChildren()) {
            prune(m, child);
        }
    }

    private boolean insideUnenteredType(final TreeSitterModel m, final TSNode node) {
        for (TSNode p = node.getParent(); p != null && !p.isNull(); p = p.getParent()) {
            final String type = p.getType();
            if ((type.equals("class_declaration") || type.equals("protocol_declaration"))
                    && m.firstChildOfType(p, "type_identifier") == null) {
                return true;
            }
        }
        return false;
    }

    protected List<String> signature(final TreeSitterModel m, final TSNode node) {
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode p = node.getNamedChild(i);
            if (p.getType().equals("parameter")) {
                final TSNode name = m.firstChildOfType(p, "simple_identifier");
                names.add(m.escape(m.textOf(name != null ? name : p).replaceAll("\\s+", "")));
            }
        }
        return names;
    }
}
