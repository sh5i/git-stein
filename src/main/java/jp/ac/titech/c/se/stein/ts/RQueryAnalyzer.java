package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterR;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based reimplementation of the imperative R visitor: detection is the declarative {@link #QUERY} (all
 * named assignments), and a bound value that is a {@code function_definition} refines the element into
 * a method ({@link #refineKind}); every other binding is a field. Naming (parameter-name signatures)
 * stays imperative.
 */
public class RQueryAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (left_assignment name: (_) @name) @field
            (equals_assignment name: (_) @name) @field
            (super_assignment name: (_) @name) @field
            """;

    public RQueryAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterR();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    /**
     * A binding whose value is a {@code function_definition} is a method; any other binding is a field.
     */
    @Override
    protected ElementKind refineKind(final ElementKind kind, final TSNode node, final Captures captures) {
        final TSNode value = node.getChildByFieldName("value");
        return !value.isNull() && value.getType().equals("function_definition")
                ? ElementKind.METHOD : ElementKind.FIELD;
    }

    @Override
    protected String name(final ElementKind kind, final TSNode node, final Captures captures) {
        final String label = flatten(textOf(captures.get("name")));
        if (kind != ElementKind.METHOD) {
            return label;
        }
        return label + "(" + signature(node.getChildByFieldName("value")) + ")";
    }

    protected String signature(final TSNode functionDefinition) {
        final TSNode params = firstChildOfType(functionDefinition, "formal_parameters");
        if (params == null) {
            return "";
        }
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < params.getNamedChildCount(); i++) {
            final TSNode child = params.getNamedChild(i);
            final TSNode name = child.getType().equals("identifier") ? child : firstChildOfType(child, "identifier");
            if (name != null) {
                names.add(escape(textOf(name).replaceAll("\\s+", "")));
            }
        }
        return String.join(",", names);
    }
}
