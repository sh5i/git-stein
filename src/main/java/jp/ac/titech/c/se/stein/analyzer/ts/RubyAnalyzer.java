package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TreeSitterRuby;

import jp.ac.titech.c.se.stein.core.SourceEncoding;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;

/**
 * A query-based reimplementation of the imperative Ruby visitor: detection is the declarative {@link #QUERY}
 * (classes, methods, and constant assignments), and only naming (parameter-name signatures) stays
 * imperative. A {@code module} is a {@code @scope} whose members nest under it by containment,
 * mirroring the visitor's naming scope.
 *
 * <p>The visitor never descends into an assignment's right-hand side, so any definition inside one
 * (e.g. the methods of {@code x = Class.new do ... end}) is not extracted. To reproduce this, the query
 * captures <em>every</em> assignment as a field so the engine drops anything nested in its right-hand
 * side; {@link #postProcess} then removes the fields that were not constant assignments, which the
 * visitor would not have emitted. A field element is always a leaf (the engine drops members under a
 * field), so removing one leaves no orphans.</p>
 */
public class RubyAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (class name: (_) @name) @class
            (module name: (_) @name) @scope
            (method name: (_) @name) @method
            (singleton_method name: (_) @name) @method
            (assignment left: (_) @name) @field
            """;

    public RubyAnalyzer() {
        super("Ruby", new NameFilter(true, "*.rb"), SourceEncoding::decodeWithMagicComment, TreeSitterRuby::new, QUERY);
    }

    @Override
    protected TreeSitterModel createModel(final String filename, final SourceText text, final TSNode treeRoot) {
        return new Model(filename, text, treeRoot, query());
    }

    static class Model extends QueryModel {
        Model(final String filename, final SourceText text, final TSNode treeRoot, final TSQuery query) {
            super(filename, text, treeRoot, query);
        }

        @Override
        protected Signature signature(final Element.Kind kind, final TSNode node, final Captures captures) {
            final String label = flatten(textOf(captures.get("name")));
            if (kind != Element.Kind.METHOD) {
                return Signature.of(label);
            }
            return new Signature(label, null, signature(node.getChildByFieldName("parameters")));
        }

        /**
         * Removes the fields whose left-hand side is not a constant: those assignments served only as
         * drop boundaries (the visitor emits only constant assignments as fields).
         */
        @Override
        protected void postProcess() {
            prune(root);
        }

        private void prune(final Element element) {
            for (final Element child : new ArrayList<>(element.getChildren())) {
                prune(child);
            }
            element.getChildren().removeIf(e -> e.getKind() == Element.Kind.FIELD && !isConstantAssignment(e));
        }

        private boolean isConstantAssignment(final Element field) {
            if (!field.hasContent()) {
                return false;
            }
            final TSNode left = nodeOf(field).getChildByFieldName("left");
            return !left.isNull() && left.getType().equals("constant");
        }

        protected List<String> signature(final TSNode parameters) {
            if (parameters == null || parameters.isNull()) {
                return List.of();
            }
            final List<String> names = new ArrayList<>();
            for (int i = 0; i < parameters.getNamedChildCount(); i++) {
                final TSNode p = parameters.getNamedChild(i);
                final TSNode name = p.getChildByFieldName("name");
                names.add(escape(textOf(name.isNull() ? p : name).replaceAll("\\s+", "")));
            }
            return names;
        }
    }
}
