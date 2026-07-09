package jp.ac.titech.c.se.stein.analyzer.ts;

import java.util.List;

import jp.ac.titech.c.se.stein.analyzer.*;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterBash;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based reimplementation of the imperative Bash visitor: function definitions become methods and
 * variable assignments become fields. A function's local assignments are dropped generically (they nest
 * under a method), and assignments the visitor never descends into — those inside a {@code command}
 * (an environment prefix or a substitution) — are pruned in {@link #postProcess}.
 */
public class BashAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (function_definition name: (_) @name) @method
            (variable_assignment name: (_) @name) @field
            """;

    public BashAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterBash();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    @Override
    protected Signature signature(final Element.Kind kind, final TSNode node, final Captures captures) {
        final String base = flatten(textOf(captures.get("name")));
        return kind == Element.Kind.METHOD ? new Signature(base, null, List.of()) : Signature.of(base);
    }

    /**
     * Drops the assignments a query captures inside a {@code command}, which the visitor treats as an
     * opaque leaf (so an environment prefix like {@code FOO=bar cmd} or a substitution is not a field).
     */
    @Override
    protected void postProcess() {
        prune(root);
    }

    private void prune(final Element element) {
        element.getChildren().removeIf(c -> c.hasContent() && insideCommand(nodeOf(c)));
        for (final Element child : element.getChildren()) {
            prune(child);
        }
    }

    private boolean insideCommand(final TSNode node) {
        for (TSNode p = node.getParent(); p != null && !p.isNull(); p = p.getParent()) {
            if (p.getType().equals("command")) {
                return true;
            }
        }
        return false;
    }
}
