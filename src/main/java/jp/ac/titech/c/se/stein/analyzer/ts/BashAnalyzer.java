package jp.ac.titech.c.se.stein.analyzer.ts;

import java.util.List;

import jp.ac.titech.c.se.stein.analyzer.*;

import org.treesitter.TSNode;
import org.treesitter.TreeSitterBash;

import jp.ac.titech.c.se.stein.core.SourceEncoding;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;

/**
 * A query-based analyzer for shell scripts: function definitions become methods and
 * variable assignments become fields. A function's local assignments are dropped generically (they nest
 * under a method), and assignments the engine never descends into — those inside a {@code command}
 * (an environment prefix or a substitution) — are pruned in {@link #postProcess}.
 */
public class BashAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (function_definition name: (_) @name) @method
            (variable_assignment name: (_) @name) @field
            """;

    public BashAnalyzer() {
        super("Shell", new NameFilter(true, "*.sh", "*.bash", "*.zsh"), SourceEncoding::decode, TreeSitterBash::new, QUERY);
    }

    @Override
    protected Signature signature(final TreeSitterModel m, final Element.Kind kind, final TSNode node, final Captures captures) {
        final String base = m.flatten(m.textOf(captures.get("name")));
        return kind == Element.Kind.METHOD ? new Signature(base, null, List.of()) : Signature.of(base);
    }

    /**
     * Drops the assignments a query captures inside a {@code command}, which is treated as an
     * opaque leaf (so an environment prefix like {@code FOO=bar cmd} or a substitution is not a field).
     */
    @Override
    protected void postProcess(final TreeSitterModel m, final Element root) {
        prune(m, root);
    }

    private void prune(final TreeSitterModel m, final Element element) {
        element.getChildren().removeIf(c -> c.hasContent() && insideCommand(m.nodeOf(c)));
        for (final Element child : element.getChildren()) {
            prune(m, child);
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
