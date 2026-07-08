package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterPhp;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based reimplementation of {@link PhpAnalyzer}: detection is the declarative {@link #QUERY}
 * (types, functions/methods, and data members), naming and signature rendering are reused from
 * {@link PhpAnalyzer}, and a block namespace ({@code namespace N { }}) is a {@code @scope} whose body
 * nests its members by containment. The one case a query cannot express — a statement namespace
 * ({@code namespace N;}) that scopes the siblings that follow it rather than a body — is handled by
 * {@link #postProcess}, exactly as C# handles a file-scoped namespace.
 */
public class PhpQueryAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (namespace_definition name: (_) @name body: (_)) @scope
            (class_declaration name: (name) @name) @class
            (interface_declaration name: (name) @name) @class
            (trait_declaration name: (name) @name) @class
            (enum_declaration name: (name) @name) @class
            (method_declaration name: (name) @name parameters: (formal_parameters) @params) @method
            (function_definition name: (name) @name parameters: (formal_parameters) @params) @method
            (property_declaration (property_element name: (variable_name) @name)) @field
            (const_declaration (const_element (name) @name)) @field
            (enum_case name: (name) @name) @field
            """;

    private final PhpAnalyzer renderer;

    public PhpQueryAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
        this.renderer = new PhpAnalyzer(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterPhp();
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
        final String raw = textOf(captures.get("name"));
        if (node.getType().equals("namespace_definition")) {
            return flatten(raw.replace("\\", "."));
        }
        return flatten(raw);
    }

    /**
     * A statement namespace ({@code namespace N;}) scopes the siblings that follow its declaration;
     * move them under it. Several such namespaces in one file chain, each scoping under the previous.
     */
    @Override
    protected void postProcess() {
        Element current = root;
        for (int i = 0; i < treeRoot.getNamedChildCount(); i++) {
            final TSNode child = treeRoot.getNamedChild(i);
            if (!child.getType().equals("namespace_definition")) {
                continue;
            }
            if (!child.getChildByFieldName("body").isNull()) {
                continue; // a block namespace is handled by containment
            }
            final TSNode name = child.getChildByFieldName("name");
            if (name.isNull()) {
                continue;
            }
            current = reparentAfter(current, flatten(textOf(name).replace("\\", ".")), child.getEndByte());
        }
    }
}
