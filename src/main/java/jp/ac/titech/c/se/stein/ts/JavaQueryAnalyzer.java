package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterJava;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based reimplementation of {@link JavaAnalyzer}: detection is the declarative {@link #QUERY}
 * (class-like declarations, methods/constructors, and one field per declarator, matched at any depth),
 * while the FinerGit method-name canonicalization and comment-attaching content are reused verbatim
 * from {@link JavaAnalyzer}. The "don't extract inside a method body, but do inside an initializer
 * block" rule falls out of the engine's generic containment: a class local to a method body nests
 * under the method (a leaf) and is dropped, while one in an initializer block nests under its class.
 */
public class JavaQueryAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (class_declaration name: (identifier) @name) @class
            (interface_declaration name: (identifier) @name) @class
            (enum_declaration name: (identifier) @name) @class
            (annotation_type_declaration name: (identifier) @name) @class
            (record_declaration name: (identifier) @name) @class
            (method_declaration) @method
            (constructor_declaration) @method
            (compact_constructor_declaration) @method
            (field_declaration (variable_declarator name: (identifier) @name)) @field
            (constant_declaration (variable_declarator name: (identifier) @name)) @field
            """;

    private final JavaAnalyzer renderer;

    public JavaQueryAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
        this.renderer = new JavaAnalyzer(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterJava();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    /**
     * Rejects anything inside a {@code class_body} that belongs to an anonymous class or an enum
     * constant rather than a type declaration: the visitor never descends into such a body (it treats
     * it like an anonymous class), so e.g. the {@code shine()} of {@code enum Color { GREEN { void
     * shine() {} } }} is not a member of {@code Color}.
     */
    @Override
    protected boolean accept(final ElementKind kind, final TSNode node, final Captures captures) {
        for (TSNode p = node.getParent(); p != null && !p.isNull(); p = p.getParent()) {
            if (p.getType().equals("class_body")) {
                final String owner = p.getParent().getType();
                if (owner.equals("enum_constant") || owner.equals("object_creation_expression")) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    protected String name(final ElementKind kind, final TSNode node, final Captures captures) {
        return kind == ElementKind.METHOD ? renderer.generateMethodName(node) : textOf(captures.get("name"));
    }

    @Override
    protected String rawContentOf(final TSNode node) {
        return renderer.rawContentOf(node);
    }

    @Override
    protected boolean isFunctionNode(final TSNode node) {
        return renderer.isFunctionNode(node);
    }
}
