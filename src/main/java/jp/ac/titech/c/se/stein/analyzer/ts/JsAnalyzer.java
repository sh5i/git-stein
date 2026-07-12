package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TreeSitterJavascript;

import jp.ac.titech.c.se.stein.core.SourceEncoding;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;

/**
 * A query-based analyzer for JavaScript: detection is the declarative {@link #QUERY} (class
 * declarations with their method and field members, top-level functions, and variable bindings),
 * while naming supplies parameter-name signatures and the method-versus-field decision for a binding.
 * Class members are captured only inside a named {@code class_declaration}, so an object-literal method
 * or a class-expression member is not extracted. The "do not descend into a function body" rule falls
 * out of the engine's generic containment: anything nested under a captured method or field is dropped.
 * Since JavaScript is untyped, a signature lists parameter names.
 */
public class JsAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (class_declaration name: (_) @name) @class
            (class_declaration body: (class_body (method_definition name: (_) @name) @method))
            (class_declaration body: (class_body (field_definition property: (_) @name) @field))
            (function_declaration name: (_) @name) @method
            (generator_function_declaration name: (_) @name) @method
            (lexical_declaration (variable_declarator name: (identifier) @name)) @field
            (variable_declaration (variable_declarator name: (identifier) @name)) @field
            (export_statement value: (_)) @field
            """;

    public JsAnalyzer() {
        super("JavaScript", new NameFilter(true, "*.js", "*.mjs", "*.cjs", "*.jsx"), SourceEncoding::decode, TreeSitterJavascript::new, QUERY);
    }

    @Override
    protected TreeSitterModel createModel(final String filename, final SourceText text, final TSNode treeRoot) {
        return new Model(filename, text, treeRoot, query());
    }

    static class Model extends QueryModel {
        Model(final String filename, final SourceText text, final TSNode treeRoot, final TSQuery query) {
            super(filename, text, treeRoot, query);
        }

        /**
         * A variable binding whose value is a function is a method; any other binding is a field. Every
         * other captured kind is kept.
         */
        @Override
        protected Element.Kind refineKind(final Element.Kind kind, final TSNode node, final Captures captures) {
            if (!isBinding(node)) {
                return kind;
            }
            final TSNode value = bindingValue(node, captures);
            return !value.isNull() && isFunction(value) ? Element.Kind.METHOD : Element.Kind.FIELD;
        }

        @Override
        protected Signature signature(final Element.Kind kind, final TSNode node, final Captures captures) {
            final TSNode nameNode = captures.get("name");
            final String leaf = nameNode == null ? "" : flatten(textOf(nameNode));
            if (kind != Element.Kind.METHOD) {
                return Signature.of(leaf);
            }
            final TSNode fn = isBinding(node) ? bindingValue(node, captures) : node;
            return new Signature(leaf, null, signature(fn));
        }

        /**
         * Drops the elements the visitor would never reach. The visitor stops walking at a declaration it
         * recognizes, so it never descends into a binding's value; anything the query captured there (a
         * function or class nested in a destructuring binding's initializer) is pruned. It also never
         * descends into an export the {@code export} path cannot dispatch — an exported ambient declaration
         * ({@code export declare ...}) or a value export ({@code export default {...}}) — whose subtree an
         * {@code @field} barrier already dropped during the build, leaving only the barrier's placeholder
         * element to remove here.
         */
        @Override
        protected void postProcess() {
            prune(root);
        }

        private void prune(final Element e) {
            e.getChildren().removeIf(c -> isBarrierPlaceholder(c) || isInsideBinding(c));
            e.getChildren().forEach(this::prune);
        }

        private boolean isBarrierPlaceholder(final Element e) {
            if (!e.hasContent()) {
                return false;
            }
            final TSNode node = nodeOf(e);
            if (!node.getType().equals("export_statement")) {
                return false;
            }
            // a barrier export tags the whole statement; a real exported element's node is an
            // export_statement whose declaration this reused (via contentNode), so keep those
            final TSNode decl = node.getChildByFieldName("declaration");
            return decl.isNull() || decl.getType().equals("ambient_declaration");
        }

        private boolean isInsideBinding(final Element e) {
            if (!e.hasContent()) {
                return false;
            }
            final TSNode node = nodeOf(e);
            final int start = node.getStartByte();
            for (TSNode p = node.getParent(); p != null && !p.isNull(); p = p.getParent()) {
                final String type = p.getType();
                if ((type.equals("lexical_declaration") || type.equals("variable_declaration"))
                        && p.getStartByte() < start) {
                    return true;
                }
            }
            return false;
        }

        /**
         * An exported declaration's content is its whole {@code export_statement}, so it includes the
         * {@code export} keyword and any decorators preceding the declaration, matching the visitor, which
         * treats the export statement as the extent.
         */
        @Override
        protected TSNode contentNode(final TSNode node) {
            final TSNode parent = node.getParent();
            if (parent != null && !parent.isNull() && parent.getType().equals("export_statement")) {
                final TSNode decl = parent.getChildByFieldName("declaration");
                if (!decl.isNull() && sameNode(decl, node)) {
                    return parent;
                }
            }
            return node;
        }

        private boolean isBinding(final TSNode node) {
            final String type = node.getType();
            return type.equals("lexical_declaration") || type.equals("variable_declaration");
        }

        /**
         * The value assigned to the captured binding, found from its name identifier's declarator.
         */
        private TSNode bindingValue(final TSNode node, final Captures captures) {
            return captures.get("name").getParent().getChildByFieldName("value");
        }

        protected boolean isFunction(final TSNode value) {
            return switch (value.getType()) {
                case "arrow_function", "function_expression", "generator_function" -> true;
                default -> false;
            };
        }

        /**
         * Renders a function's parameter names, dropping default values; a single unparenthesized arrow
         * parameter is held under a {@code parameter} field instead of {@code parameters}.
         */
        protected List<String> signature(final TSNode fn) {
            final TSNode parameters = fn.getChildByFieldName("parameters");
            if (!parameters.isNull()) {
                final List<String> names = new ArrayList<>();
                for (int i = 0; i < parameters.getNamedChildCount(); i++) {
                    names.add(paramName(parameters.getNamedChild(i)));
                }
                return names;
            }
            final TSNode single = fn.getChildByFieldName("parameter");
            return single.isNull() ? List.of() : List.of(paramName(single));
        }

        protected String paramName(final TSNode p) {
            if (p.getType().equals("assignment_pattern")) {
                return paramName(p.getChildByFieldName("left"));
            }
            return escape(textOf(p).replaceAll("\\s+", ""));
        }
    }
}
