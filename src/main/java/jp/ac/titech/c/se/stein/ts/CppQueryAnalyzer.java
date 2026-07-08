package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterC;
import org.treesitter.TreeSitterCpp;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based reimplementation of {@link CppAnalyzer}, which serves both C and C++: detection is the
 * declarative {@link #CPP_QUERY} (or {@link #C_QUERY} for a {@code .c} file, whose grammar has neither
 * classes, namespaces, nor templates), while the declarator unwrapping, parameter-type signature, and
 * comment-free line content stay imperative, reused verbatim from {@link CppAnalyzer}.
 *
 * <p>Three rules a query cannot express are handled by {@link #accept}: a member-function prototype (a
 * field declarator that unwraps to a function) and a variable/prototype whose declarator names nothing
 * are dropped, and a type specifier used as the {@code type} of a declaration or a class-body field —
 * which the visitor never descends into — is dropped, so its members do not leak out (a bare nested
 * {@code struct N { ... };} inside a class parses as a member field with no declarator, and is not
 * extracted, whereas the same struct at namespace or file scope, or under a {@code typedef}, is).
 * Function bodies are not descended: a local class nests under its enclosing method and the engine
 * drops it. A template header, which the visitor folds into the extent, is restored by widening the
 * rendered content of a specifier or function directly wrapped in a {@code template_declaration}.</p>
 */
public class CppQueryAnalyzer extends QueryAnalyzer {
    private static final String CPP_QUERY = """
            (namespace_definition name: (_) @name) @scope
            (class_specifier name: (_) @name) @class
            (struct_specifier name: (_) @name) @class
            (union_specifier name: (_) @name) @class
            (enum_specifier name: (_) @name) @class
            (function_definition) @method
            (field_declaration declarator: (_) @name) @field
            (declaration declarator: (_) @name) @field
            """;

    private static final String C_QUERY = """
            (struct_specifier name: (_) @name) @class
            (union_specifier name: (_) @name) @class
            (enum_specifier name: (_) @name) @class
            (function_definition) @method
            (field_declaration declarator: (_) @name) @field
            (declaration declarator: (_) @name) @field
            """;

    private final CppAnalyzer renderer;

    private final boolean isC;

    public CppQueryAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
        this.renderer = new CppAnalyzer(filename, text, treeRoot);
        this.isC = filename.endsWith(".c");
    }

    @Override
    protected TSLanguage grammar() {
        return isC ? new TreeSitterC() : new TreeSitterCpp();
    }

    @Override
    protected String queryString() {
        return isC ? C_QUERY : CPP_QUERY;
    }

    @Override
    protected String name(final ElementKind kind, final TSNode node, final Captures captures) {
        if (kind == ElementKind.METHOD) {
            final TSNode fd = renderer.functionDeclarator(node.getChildByFieldName("declarator"));
            final TSNode name = fd.getChildByFieldName("declarator");
            return flatten(textOf(name)) + "(" + renderer.signature(fd.getChildByFieldName("parameters")) + ")";
        }
        if (kind == ElementKind.FIELD) {
            return flatten(textOf(fieldNameNode(node, captures.get("name"))));
        }
        return flatten(textOf(captures.get("name")));
    }

    @Override
    protected boolean accept(final ElementKind kind, final TSNode node, final Captures captures) {
        if (templateGated(node)) {
            return false; // inside a template_declaration on a path the visitor's visitTemplate ignores
        }
        if (inTypeSpecifier(node)) {
            return false; // inside a specifier used as a declaration/field type, which the visitor never enters
        }
        if (hasAncestorType(node, "enum_specifier")) {
            return false; // an enum body is not walked, so an elaborated type reference in a value is not extracted
        }
        switch (kind) {
            case METHOD -> {
                final TSNode fd = renderer.functionDeclarator(node.getChildByFieldName("declarator"));
                return fd != null && !fd.getChildByFieldName("declarator").isNull();
            }
            case FIELD -> {
                // the visitor skips the whole declaration when its first declarator is a function
                // (a prototype); otherwise it names each declared member
                if (renderer.functionDeclarator(node.getChildByFieldName("declarator")) != null) {
                    return false;
                }
                return fieldNameNode(node, captures.get("name")) != null;
            }
            default -> {
                return true;
            }
        }
    }

    @Override
    protected String rawContentOf(final TSNode node) {
        return renderer.rawContentOf(extentOf(node));
    }

    /**
     * Whether a node is hidden by an enclosing {@code template_declaration}. The visitor's
     * {@code visitTemplate} extracts only the template's direct class/struct/union/enum/function child
     * (and, for a class, walks its body); a template whose child is anything else — a friend
     * declaration, a variable/prototype {@code declaration}, a concept, or a further nested
     * {@code template_declaration} — is not descended, so such a child and everything under it is
     * invisible. At every enclosing template, the child on the path must be one of those handled node
     * types, or the node is gated out.
     */
    private boolean templateGated(final TSNode node) {
        for (TSNode child = node, p = node.getParent(); p != null && !p.isNull(); child = p, p = p.getParent()) {
            if (p.getType().equals("template_declaration")) {
                final boolean handled = switch (child.getType()) {
                    case "class_specifier", "struct_specifier", "union_specifier", "enum_specifier",
                         "function_definition" -> true;
                    default -> false;
                };
                if (!handled) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a node is (or is inside) a class/struct/union/enum specifier that serves as the
     * {@code type} of a {@code declaration} or a class-body {@code field_declaration}. The visitor
     * handles such a declaration with {@code visitDeclaration}/{@code visitField}, which never descend
     * into the type, so the specifier and everything under it — including the members of an anonymous
     * inline {@code union}/{@code struct} — are invisible. A specifier at namespace or file scope, or
     * under a {@code typedef}, is reached by the walk and so is not gated here.
     */
    private boolean inTypeSpecifier(final TSNode node) {
        for (TSNode n = node; n != null && !n.isNull(); n = n.getParent()) {
            if (isSpecifier(n)) {
                final TSNode p = n.getParent();
                if (p != null && !p.isNull()
                        && (p.getType().equals("field_declaration") || p.getType().equals("declaration"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasAncestorType(final TSNode node, final String type) {
        for (TSNode p = node.getParent(); p != null && !p.isNull(); p = p.getParent()) {
            if (p.getType().equals(type)) {
                return true;
            }
        }
        return false;
    }

    private boolean isSpecifier(final TSNode node) {
        return switch (node.getType()) {
            case "class_specifier", "struct_specifier", "union_specifier", "enum_specifier" -> true;
            default -> false;
        };
    }

    /**
     * The name of a data member, matching whichever traversal {@link CppAnalyzer} uses for the
     * containing declaration. A class-body {@code field_declaration} uses {@code collectFieldNames}
     * (only a {@code field_identifier}, reached solely through nested {@code *declarator}s), so a
     * spurious identifier inside an error-recovery subtree is not mistaken for the name; a namespace or
     * file-scope {@code declaration} unwraps an {@code init_declarator} and uses {@code declaratorName}.
     * Returns null when the declaration names no data member (e.g. a friend declaration).
     */
    private TSNode fieldNameNode(final TSNode declaration, final TSNode declarator) {
        if (declaration.getType().equals("field_declaration")) {
            // mirror collectFieldNames applied to this declarator: a field_identifier names it, a
            // nested *declarator is descended for one, and anything else (e.g. a template_method the
            // parser produced from a macro-prefixed member) contributes no name
            if (declarator.getType().equals("field_identifier")) {
                return declarator;
            }
            if (!declarator.getType().endsWith("declarator")) {
                return null;
            }
            final List<TSNode> names = new ArrayList<>();
            renderer.collectFieldNames(declarator, names);
            return names.isEmpty() ? null : names.get(0);
        }
        final TSNode inner = declarator.getType().equals("init_declarator")
                ? declarator.getChildByFieldName("declarator") : declarator;
        return renderer.declaratorName(inner);
    }

    /**
     * The node whose lines are the element's content: a specifier or function directly wrapped in a
     * {@code template_declaration} widens to the template so the template header is included, matching
     * the visitor's extent.
     */
    private TSNode extentOf(final TSNode node) {
        final TSNode parent = node.getParent();
        return parent != null && !parent.isNull() && parent.getType().equals("template_declaration")
                ? parent : node;
    }
}
