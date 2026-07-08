package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterDart;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based reimplementation of {@link DartAnalyzer}: detection is the declarative {@link #QUERY},
 * and only the signature and name rendering (getter/setter/factory/constructor selection) stays
 * imperative, reused from {@link DartAnalyzer}. The Dart grammar splits a function into a signature
 * node and a separate body node, so a method spans the two via the adjacent {@code @body} capture.
 *
 * <p>The detection mirrors the visitor's structural walk precisely. Class and extension members are
 * matched only under the {@code body} of a <em>named</em> {@code class_definition}/
 * {@code extension_declaration} (a {@code mixin_declaration} and an unnamed extension have no
 * {@code name} field, so the visitor skips them), and an abstract member, a {@code static} field, a
 * {@code const} constructor, and an operator (none of which the visitor extracts) are excluded by
 * matching only the name-bearing signature shapes. The visitor also descends every non-member function
 * body (they are separate sibling nodes, so its {@code walk} recurses into them) and flattens the
 * named local functions it finds to the file root; those are captured via {@code lambda_expression}.
 * The reverse — a local function inside a class member's body, which the visitor does not reach —
 * would nest under the enclosing class by containment, so {@link #postProcess} prunes it.</p>
 */
public class DartQueryAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (class_definition name: (identifier) @name) @class
            (enum_declaration name: (identifier) @name) @class
            (extension_declaration name: (identifier) @name) @class

            (enum_declaration body: (enum_body (enum_constant name: (identifier) @name) @field))

            (program (initialized_identifier_list (initialized_identifier . (identifier) @name)) @field)
            (program (function_signature) @method . (function_body)? @body)
            (program (method_signature [(function_signature) (getter_signature) (setter_signature) (factory_constructor_signature) (constructor_signature)]) @method . (function_body)? @body)

            (class_definition body: (class_body (method_signature [(function_signature) (getter_signature) (setter_signature) (factory_constructor_signature) (constructor_signature)]) @method . (function_body)? @body))
            (class_definition body: (class_body (declaration (initialized_identifier_list (initialized_identifier . (identifier) @name))) @field))
            (class_definition body: (class_body (declaration (constructor_signature)) @method))

            (extension_declaration name: (identifier) body: (extension_body (method_signature [(function_signature) (getter_signature) (setter_signature) (factory_constructor_signature) (constructor_signature)]) @method . (function_body)? @body))
            (extension_declaration name: (identifier) body: (extension_body (declaration (initialized_identifier_list (initialized_identifier . (identifier) @name))) @field))
            (extension_declaration name: (identifier) body: (extension_body (declaration (constructor_signature)) @method))

            (lambda_expression parameters: (function_signature) @method body: (function_body) @body)
            """;

    private final DartAnalyzer renderer;

    public DartQueryAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
        this.renderer = new DartAnalyzer(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterDart();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    @Override
    protected String name(final ElementKind kind, final TSNode node, final Captures captures) {
        if (kind != ElementKind.METHOD) {
            return flatten(textOf(captures.get("name")));
        }
        if (node.getType().equals("declaration")) {
            final TSNode constructor = firstChildOfType(node, "constructor_signature");
            final TSNode name = renderer.lastChildOfType(constructor, "identifier");
            return flatten(textOf(name)) + "(" + renderer.signature(constructor) + ")";
        }
        final TSNode sig = node.getType().equals("method_signature") ? renderer.firstSignature(node) : node;
        final TSNode name = renderer.signatureName(sig);
        return flatten(textOf(name)) + "(" + renderer.signature(sig) + ")";
    }

    /**
     * The visitor extracts a named local function only from a top-level function's body; it never
     * recurses the body of a class, mixin, extension, or enum member (a mixin and an unnamed extension
     * it skips wholesale). A local function captured inside any of those is therefore spurious: it is a
     * {@code function_signature} whose node has such an enclosing type — a top-level function, by
     * contrast, has none — so prune it wherever it landed (under its class, or, for a mixin or unnamed
     * extension that is not itself an element, floated to the file root).
     */
    @Override
    protected void postProcess() {
        prune(root);
    }

    private void prune(final Element element) {
        element.getChildren().removeIf(c -> c.node != null
                && c.node.getType().equals("function_signature") && hasEnclosingType(c.node));
        for (final Element child : element.getChildren()) {
            prune(child);
        }
    }

    private boolean hasEnclosingType(final TSNode node) {
        for (TSNode p = node.getParent(); p != null && !p.isNull(); p = p.getParent()) {
            switch (p.getType()) {
                case "class_definition", "mixin_declaration", "extension_declaration", "enum_declaration" -> {
                    return true;
                }
                default -> {
                }
            }
        }
        return false;
    }
}
