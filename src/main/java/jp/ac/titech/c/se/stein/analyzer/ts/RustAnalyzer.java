package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;
import org.treesitter.TreeSitterRust;

import jp.ac.titech.c.se.stein.core.SourceEncoding;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;

/**
 * A query-based analyzer for Rust: detection is the declarative {@link #QUERY} and only naming
 * (receiver-free method signatures and the base type name of an {@code impl}) stays imperative. An
 * {@code impl} block and a module are {@code @scope}s whose members nest under them by containment
 * (the {@code impl}'s methods attach to the implemented type); struct and union fields, and trait
 * method signatures, are scoped to their declaring body so that enum-variant fields, tuple-struct
 * fields, and associated constants — which are not extracted — stay out, and constants/statics/type
 * aliases are scoped to the file or a module body so that {@code impl}/trait members are not mistaken
 * for them.
 */
public class RustAnalyzer extends QueryAnalyzer {
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

    public RustAnalyzer() {
        super("Rust", new NameFilter(true, "*.rs"), SourceEncoding::decode, TreeSitterRust::new, QUERY);
    }

    @Override
    protected Signature signature(final TreeSitterModel m, final Element.Kind kind, final TSNode node, final Captures captures) {
        if (kind == Element.Kind.METHOD) {
            return new Signature(m.flatten(m.textOf(captures.get("name"))), null, signature(m, captures.get("params")));
        }
        if (captures.has("type")) {
            return Signature.of(m.flatten(baseTypeName(m, captures.get("type"))));
        }
        return Signature.of(m.flatten(m.textOf(captures.get("name"))));
    }

    /**
     * The base name of a type, dropping generic arguments, e.g. {@code Foo} for {@code Foo<T>}.
     */
    protected String baseTypeName(final TreeSitterModel m, final TSNode type) {
        if (type.getType().equals("generic_type")) {
            final TSNode base = m.firstChildOfType(type, "type_identifier");
            return base != null ? m.textOf(base) : m.textOf(type);
        }
        return m.textOf(type);
    }

    protected List<String> signature(final TreeSitterModel m, final TSNode parameters) {
        if (parameters == null || parameters.isNull()) {
            return List.of();
        }
        final List<String> types = new ArrayList<>();
        for (int i = 0; i < parameters.getNamedChildCount(); i++) {
            final TSNode p = parameters.getNamedChild(i);
            if (p.getType().equals("parameter")) {
                types.add(m.escape(m.textOf(p.getChildByFieldName("type")).replaceAll("\\s+", "")));
            } else if (p.getType().equals("self_parameter")) {
                types.add("self");
            }
        }
        return types;
    }
}
