package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;
import org.treesitter.TreeSitterDart;


/**
 * A query-based analyzer for Dart: detection is the declarative {@link #QUERY}, and the signature and
 * name rendering (getter/setter/factory/constructor selection) is done imperatively. The Dart grammar
 * splits a function into a signature node and a separate body node, so a method spans the two via the
 * adjacent {@code @body} capture.
 *
 * <p>Class and extension members are matched only under the {@code body} of a <em>named</em>
 * {@code class_definition}/{@code extension_declaration} (a {@code mixin_declaration} and an unnamed
 * extension have no {@code name} field, so they are skipped), and an abstract member, a {@code static}
 * field, a {@code const} constructor, and an operator (none of which are extracted) are excluded by
 * matching only the name-bearing signature shapes. Every non-member function body is descended (they
 * are separate sibling nodes), flattening the named local functions to the file root; those are
 * captured via {@code lambda_expression}. The reverse — a local function inside a class member's body,
 * which is not reached — would nest under the enclosing class by containment, so {@link #postProcess}
 * prunes it.</p>
 */
public class DartAnalyzer extends QueryAnalyzer {
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

    public DartAnalyzer() {
        super(Language.DART, TreeSitterDart::new, QUERY);
    }

    @Override
    protected Signature signature(final TreeSitterModel m, final Element.Kind kind, final TSNode node, final Captures captures) {
        if (kind != Element.Kind.METHOD) {
            return Signature.of(m.flatten(m.textOf(captures.get("name"))));
        }
        if (node.getType().equals("declaration")) {
            final TSNode constructor = m.firstChildOfType(node, "constructor_signature");
            final TSNode name = lastChildOfType(constructor, "identifier");
            return new Signature(m.flatten(m.textOf(name)), null, signature(m, constructor));
        }
        final TSNode sig = node.getType().equals("method_signature") ? firstSignature(node) : node;
        final TSNode name = signatureName(sig);
        return new Signature(m.flatten(m.textOf(name)), null, signature(m, sig));
    }

    /**
     * A named local function is extracted only from a top-level function's body; the body of a class,
     * mixin, extension, or enum member is never recursed (a mixin and an unnamed extension are skipped
     * wholesale). A local function captured inside any of those is therefore spurious: it is a
     * {@code function_signature} whose node has such an enclosing type — a top-level function, by
     * contrast, has none — so prune it wherever it landed (under its class, or, for a mixin or unnamed
     * extension that is not itself an element, floated to the file root).
     */
    @Override
    protected void postProcess(final TreeSitterModel m, final Element root) {
        prune(m, root);
    }

    private void prune(final TreeSitterModel m, final Element element) {
        element.getChildren().removeIf(c -> {
            if (!c.hasContent()) {
                return false;
            }
            final TSNode node = m.nodeOf(c);
            return node.getType().equals("function_signature") && hasEnclosingType(node);
        });
        for (final Element child : element.getChildren()) {
            prune(m, child);
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

    /**
     * The signature node inside a method signature: a function, getter, setter, or factory constructor.
     */
    protected TSNode firstSignature(final TSNode methodSignature) {
        for (int i = 0; i < methodSignature.getNamedChildCount(); i++) {
            final TSNode child = methodSignature.getNamedChild(i);
            if (child.getType().endsWith("_signature")) {
                return child;
            }
        }
        return null;
    }

    /**
     * The declared name of a signature. A factory constructor names the factory (its last identifier);
     * every other signature carries a {@code name} field.
     */
    protected TSNode signatureName(final TSNode sig) {
        final TSNode name = sig.getChildByFieldName("name");
        return name.isNull() ? lastChildOfType(sig, "identifier") : name;
    }

    protected TSNode lastChildOfType(final TSNode node, final String type) {
        TSNode found = null;
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            if (child.getType().equals(type)) {
                found = child;
            }
        }
        return found;
    }

    protected List<String> signature(final TreeSitterModel m, final TSNode functionSignature) {
        final TSNode params = m.firstChildOfType(functionSignature, "formal_parameter_list");
        if (params == null) {
            return List.of();
        }
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < params.getNamedChildCount(); i++) {
            final TSNode name = params.getNamedChild(i).getChildByFieldName("name");
            if (!name.isNull()) {
                names.add(m.escape(m.textOf(name).replaceAll("\\s+", "")));
            }
        }
        return names;
    }
}
