package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterPhp;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based analyzer for PHP: detection is the declarative {@link #QUERY}
 * (types, functions/methods, and data members), and a block namespace ({@code namespace N { }}) is a
 * {@code @scope} whose body nests its members by containment. The one case a query cannot express — a
 * statement namespace ({@code namespace N;}) that scopes the siblings that follow it rather than a
 * body — is handled by {@link #postProcess}, exactly as C# handles a file-scoped namespace.
 */
public class PhpAnalyzer extends QueryAnalyzer {
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

    public PhpAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
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
    protected Signature signature(final Element.Kind kind, final TSNode node, final Captures captures) {
        if (kind == Element.Kind.METHOD) {
            return new Signature(flatten(textOf(captures.get("name"))), null, signature(captures.get("params")));
        }
        final String raw = textOf(captures.get("name"));
        if (node.getType().equals("namespace_definition")) {
            return Signature.of(flatten(raw.replace("\\", ".")));
        }
        return Signature.of(flatten(raw));
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
            current = reparentAfter(current, flatten(textOf(name).replace("\\", ".")), text.toCharIndex(child.getEndByte()));
        }
    }

    protected List<String> signature(final TSNode parameters) {
        if (parameters.isNull()) {
            return List.of();
        }
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < parameters.getNamedChildCount(); i++) {
            final TSNode name = parameters.getNamedChild(i).getChildByFieldName("name");
            if (!name.isNull()) {
                names.add(escape(textOf(name).replaceAll("\\s+", "")));
            }
        }
        return names;
    }
}
