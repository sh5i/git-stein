package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterObjc;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based reimplementation of {@link ObjcAnalyzer}: detection is the declarative {@link #QUERY}
 * ({@code @interface}/{@code @implementation} blocks and their direct method and data members), while
 * naming — a method's selector — is reused from {@link ObjcAnalyzer}. Members are matched only as
 * direct children of a class node, so declarations elsewhere (e.g. a {@code @protocol}'s methods, which
 * the visitor does not extract) are left out.
 */
public class ObjcQueryAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (class_interface name: (identifier) @name) @class
            (class_implementation name: (identifier) @name) @class
            (category_interface name: (identifier) @name) @class
            (category_implementation name: (identifier) @name) @class

            (class_interface (method_declaration) @method)
            (class_interface (method_definition) @method)
            (class_interface (property_declaration declarator: (_)) @field)
            (class_interface (field_declaration declarator: (_)) @field)

            (category_interface (method_declaration) @method)
            (category_interface (property_declaration declarator: (_)) @field)
            (category_interface (field_declaration declarator: (_)) @field)

            (class_implementation (method_definition) @method)
            (class_implementation (field_declaration declarator: (_)) @field)

            (category_implementation (method_definition) @method)
            """;

    private final ObjcAnalyzer renderer;

    public ObjcQueryAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
        this.renderer = new ObjcAnalyzer(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterObjc();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    @Override
    protected String name(final ElementKind kind, final TSNode node, final Captures captures) {
        if (kind == ElementKind.METHOD) {
            return flatten(renderer.selectorName(node));
        }
        if (kind == ElementKind.FIELD) {
            // one field per declaration named by its first declarator, as the visitor does; a
            // declaration with several declarators (e.g. size_t w, h) still yields a single field
            return flatten(textOf(node.getChildByFieldName("declarator")));
        }
        return flatten(textOf(captures.get("name")));
    }
}
