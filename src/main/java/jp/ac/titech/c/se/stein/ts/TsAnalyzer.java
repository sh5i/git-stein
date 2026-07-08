package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterTypescript;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based analyzer for TypeScript. TypeScript is a superset of JavaScript, so this reuses
 * {@link JsAnalyzer}'s naming and binding logic and supplies a query that adds the TypeScript
 * constructs: abstract classes and their members, interfaces (classes whose method and property
 * signatures are members), enums (classes), type aliases (fields), and namespaces
 * ({@code internal_module}/{@code module}) as {@code @scope}. Parameter types are stripped, leaving
 * parameter names.
 */
public class TsAnalyzer extends JsAnalyzer {
    private static final String QUERY = """
            (class_declaration name: (_) @name) @class
            (class_declaration body: (class_body (method_definition name: (_) @name) @method))
            (class_declaration body: (class_body (public_field_definition name: (_) @name) @field))
            (abstract_class_declaration name: (_) @name) @class
            (abstract_class_declaration body: (class_body (method_definition name: (_) @name) @method))
            (abstract_class_declaration body: (class_body (public_field_definition name: (_) @name) @field))
            (function_declaration name: (_) @name) @method
            (generator_function_declaration name: (_) @name) @method
            (lexical_declaration (variable_declarator name: (identifier) @name)) @field
            (variable_declaration (variable_declarator name: (identifier) @name)) @field
            (interface_declaration name: (_) @name) @class
            (interface_declaration body: (interface_body (method_signature name: (_) @name) @method))
            (interface_declaration body: (interface_body (property_signature name: (_) @name) @field))
            (enum_declaration name: (_) @name) @class
            (type_alias_declaration name: (_) @name) @field
            (internal_module name: (_) @name) @scope
            (module name: (_) @name) @scope
            (export_statement value: (_)) @field
            (export_statement declaration: (ambient_declaration)) @field
            """;

    public TsAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterTypescript();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    @Override
    protected String paramName(final TSNode p) {
        return switch (p.getType()) {
            case "required_parameter", "optional_parameter" -> paramName(p.getChildByFieldName("pattern"));
            default -> super.paramName(p);
        };
    }
}
