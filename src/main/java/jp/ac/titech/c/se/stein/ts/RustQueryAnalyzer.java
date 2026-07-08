package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterRust;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based reimplementation of {@link RustAnalyzer}: detection is the declarative {@link #QUERY}
 * and only naming (receiver-free method signatures and the base type name of an {@code impl}) stays
 * imperative, reused verbatim from {@link RustAnalyzer}. An {@code impl} block and a module are
 * {@code @scope}s whose members nest under them by containment (the {@code impl}'s methods attach to
 * the implemented type); struct and union fields, and trait method signatures, are scoped to their
 * declaring body so that enum-variant fields, tuple-struct fields, and associated constants — which
 * the visitor does not extract — stay out, and constants/statics/type aliases are scoped to the file
 * or a module body so that {@code impl}/trait members are not mistaken for them.
 */
public class RustQueryAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (struct_item name: (type_identifier) @name) @class
            (enum_item name: (type_identifier) @name) @class
            (union_item name: (type_identifier) @name) @class
            (trait_item name: (type_identifier) @name) @class
            (mod_item name: (identifier) @name) @scope
            (impl_item type: (_) @type) @scope
            (function_item name: (identifier) @name parameters: (parameters) @params) @method
            (struct_item body: (field_declaration_list
                (field_declaration name: (field_identifier) @name) @field))
            (union_item body: (field_declaration_list
                (field_declaration name: (field_identifier) @name) @field))
            (trait_item body: (declaration_list
                (function_signature_item name: (identifier) @name parameters: (parameters) @params) @method))
            (source_file (const_item name: (identifier) @name) @field)
            (source_file (static_item name: (identifier) @name) @field)
            (source_file (type_item name: (type_identifier) @name) @field)
            (mod_item body: (declaration_list (const_item name: (identifier) @name) @field))
            (mod_item body: (declaration_list (static_item name: (identifier) @name) @field))
            (mod_item body: (declaration_list (type_item name: (type_identifier) @name) @field))
            """;

    private final RustAnalyzer renderer;

    public RustQueryAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
        this.renderer = new RustAnalyzer(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterRust();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    @Override
    protected String name(final ElementKind kind, final TSNode node, final Captures captures) {
        if (kind == ElementKind.METHOD) {
            return flatten(textOf(captures.get("name"))) + "(" + renderer.signature(captures.get("params")) + ")";
        }
        if (captures.has("type")) {
            return flatten(renderer.baseTypeName(captures.get("type")));
        }
        return flatten(textOf(captures.get("name")));
    }
}
