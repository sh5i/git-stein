package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TreeSitterR;

import jp.ac.titech.c.se.stein.core.SourceEncoding;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;

/**
 * A query-based reimplementation of the imperative R visitor: detection is the declarative {@link #QUERY} (all
 * named assignments), and a bound value that is a {@code function_definition} refines the element into
 * a method ({@link #refineKind}); every other binding is a field. Naming (parameter-name signatures)
 * stays imperative.
 */
public class RAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (left_assignment name: (_) @name) @field
            (equals_assignment name: (_) @name) @field
            (super_assignment name: (_) @name) @field
            """;

    public RAnalyzer() {
        super("R", new NameFilter(true, "*.r"), SourceEncoding::decode, TreeSitterR::new, QUERY);
    }

    @Override
    protected TreeSitterModel createModel(final String filename, final SourceText text, final TSNode treeRoot) {
        return new Model(filename, text, treeRoot, query());
    }

    static class Model extends QueryModel {
        Model(final String filename, final SourceText text, final TSNode treeRoot, final TSQuery query) {
            super(filename, text, treeRoot, query);
        }

        /**
         * A binding whose value is a {@code function_definition} is a method; any other binding is a field.
         */
        @Override
        protected Element.Kind refineKind(final Element.Kind kind, final TSNode node, final Captures captures) {
            final TSNode value = node.getChildByFieldName("value");
            return !value.isNull() && value.getType().equals("function_definition")
                    ? Element.Kind.METHOD : Element.Kind.FIELD;
        }

        @Override
        protected Signature signature(final Element.Kind kind, final TSNode node, final Captures captures) {
            final String label = flatten(textOf(captures.get("name")));
            if (kind != Element.Kind.METHOD) {
                return Signature.of(label);
            }
            return new Signature(label, null, signature(node.getChildByFieldName("value")));
        }

        protected List<String> signature(final TSNode functionDefinition) {
            final TSNode params = firstChildOfType(functionDefinition, "formal_parameters");
            if (params == null) {
                return List.of();
            }
            final List<String> names = new ArrayList<>();
            for (int i = 0; i < params.getNamedChildCount(); i++) {
                final TSNode child = params.getNamedChild(i);
                final TSNode name = child.getType().equals("identifier") ? child : firstChildOfType(child, "identifier");
                if (name != null) {
                    names.add(escape(textOf(name).replaceAll("\\s+", "")));
                }
            }
            return names;
        }
    }
}
