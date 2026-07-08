package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCSharp;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based reimplementation of {@link CSharpAnalyzer}: detection is the declarative {@link #QUERY}
 * (types, methods, and data members), naming is reused from {@link CSharpAnalyzer}, and a regular
 * namespace is a {@code @scope} whose body nests its members by containment. The one case a query
 * cannot express — a file-scoped namespace ({@code namespace N;}) that scopes the siblings that follow
 * it rather than a body — is handled by {@link #postProcess}.
 */
public class CSharpQueryAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (class_declaration name: (identifier) @name) @class
            (struct_declaration name: (identifier) @name) @class
            (interface_declaration name: (identifier) @name) @class
            (record_declaration name: (identifier) @name) @class
            (enum_declaration name: (identifier) @name) @class
            (namespace_declaration name: (_) @name) @scope
            (method_declaration) @method
            (constructor_declaration) @method
            (destructor_declaration) @method
            (operator_declaration) @method
            (field_declaration (variable_declaration (variable_declarator name: (identifier) @name))) @field
            (event_field_declaration (variable_declaration (variable_declarator name: (identifier) @name))) @field
            (property_declaration name: (identifier) @name) @field
            """;

    private final CSharpAnalyzer renderer;

    public CSharpQueryAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
        this.renderer = new CSharpAnalyzer(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterCSharp();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    @Override
    protected String name(final ElementKind kind, final TSNode node, final Captures captures) {
        return kind == ElementKind.METHOD ? renderer.methodName(node) : flatten(textOf(captures.get("name")));
    }

    @Override
    protected String rawContentOf(final TSNode node) {
        return renderer.rawContentOf(node);
    }

    /**
     * A file-scoped namespace scopes the siblings that follow its declaration; move them under it.
     */
    @Override
    protected void postProcess() {
        final TSNode fileScoped = firstChildOfType(treeRoot, "file_scoped_namespace_declaration");
        if (fileScoped == null) {
            return;
        }
        final TSNode name = fileScoped.getChildByFieldName("name");
        if (!name.isNull()) {
            reparentAfter(root, flatten(textOf(name)), fileScoped.getEndByte());
        }
    }
}
