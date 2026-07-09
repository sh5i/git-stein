package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A {@link LanguageAnalyzer} whose element <em>detection</em> is declarative: a subclass supplies a
 * tree-sitter query that captures each element node (tagged {@code @class}, {@code @method},
 * {@code @field}, or {@code @scope}) together with naming handles ({@code @name}, {@code @params},
 * {@code @receiver}, ...) and, for grammars that split a declaration, an adjacent body ({@code @body},
 * which makes the element span the two). This engine runs the query, rebuilds the element tree from
 * the captures by source containment, and asks the subclass only to <em>name</em> each element. The
 * per-language imperative code thus shrinks to naming/signature rendering.
 *
 * <p>Two extraction rules that a query cannot express are handled here generically: an element whose
 * nearest enclosing element is a method is dropped (a method has no members, so a class local to a
 * method body is not extracted, while one in an initializer block — which is not a captured element —
 * survives under its enclosing class); and members nest under the innermost enclosing captured
 * element. A stateful case a query still cannot handle (a file-scoped namespace that scopes its later
 * siblings) is left to a subclass hook.</p>
 */
public abstract class QueryAnalyzer extends LanguageAnalyzer {
    private static final Map<String, TSQuery> QUERY_CACHE = new ConcurrentHashMap<>();

    protected QueryAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    /**
     * The grammar the query is written against (the same grammar the blob was parsed with).
     */
    protected abstract TSLanguage grammar();

    /**
     * The tree-sitter query capturing this language's elements.
     */
    protected abstract String queryString();

    /**
     * The module name of a detected element, computed from its node and captured naming handles.
     */
    protected abstract String name(ElementKind kind, TSNode node, Captures captures);

    /**
     * Refines the kind a capture tag gave an element, for a language whose kind depends on a child a
     * query cannot condition on (e.g. a Go {@code type} spec is a class when it aliases a struct or
     * interface, otherwise a field). The default keeps the captured kind.
     */
    protected ElementKind refineKind(final ElementKind kind, final TSNode node, final Captures captures) {
        return kind;
    }

    /**
     * Whether a detected element should be kept. A query captures by node type, but a language may need
     * to reject a match on a structural condition a query cannot express (e.g. a C++ field declarator
     * that unwraps to a function prototype rather than a data member). A rejected match is treated as a
     * dropped scope, so anything nested inside it is dropped too — the visitor does not descend into a
     * skipped declaration. The default keeps every match.
     */
    protected boolean accept(final ElementKind kind, final TSNode node, final Captures captures) {
        return true;
    }

    /**
     * The node whose text becomes an element's content, given its detected node. The default is the
     * node itself; a subclass widens it to an enclosing wrapper the visitor treated as the element's
     * extent (e.g. JavaScript's {@code export_statement}, so an exported declaration's content includes
     * the {@code export} keyword and any leading decorators). Only the content is affected; nesting and
     * ordering still use the detected node.
     */
    protected TSNode contentNode(final TSNode node) {
        return node;
    }

    private TSQuery query() {
        // key on the query text too: one analyzer class may compile different queries for related
        // grammars (e.g. the C++ analyzer serves both the C and C++ grammars)
        return QUERY_CACHE.computeIfAbsent(getClass().getName() + "\0" + queryString(),
                k -> new TSQuery(grammar(), queryString()));
    }

    @Override
    protected void run() {
        final List<Detected> found = detect();
        // outermost first at the same start; among elements sharing a node (multiple names in one
        // declaration), order by the name's position so they come out in source order
        found.sort((a, b) -> {
            if (a.start != b.start) {
                return Integer.compare(a.start, b.start);
            }
            if (a.end != b.end) {
                return Integer.compare(b.end, a.end);
            }
            return Integer.compare(a.nameStart, b.nameStart);
        });
        build(found);
        postProcess();
    }

    /**
     * A hook for the stateful scoping a query cannot express, run after the tree is built. The default
     * does nothing; C# overrides it to move the siblings that follow a file-scoped namespace under it.
     */
    protected void postProcess() {
    }

    /**
     * Reparents the given element's later children (those starting at or after {@code fromByte}) under
     * a new scope appended to it; used to realize a file-scoped namespace.
     */
    protected Element reparentAfter(final Element parent, final String scopeName, final int fromByte) {
        final Element scope = new Element(ElementKind.CLASS, scopeName, null);
        final List<Element> children = parent.getChildren();
        final List<Element> moved = new ArrayList<>();
        children.removeIf(e -> {
            if (e.getStartByte() >= fromByte) {
                moved.add(e);
                return true;
            }
            return false;
        });
        moved.forEach(scope::addChild);
        parent.addChild(scope);
        return scope;
    }

    private List<Detected> detect() {
        final TSQuery query = query();
        final TSQueryCursor cursor = new TSQueryCursor();
        cursor.exec(query, treeRoot);
        final TSQueryMatch match = new TSQueryMatch();
        final List<Detected> out = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        while (cursor.nextMatch(match)) {
            final Detected d = new Detected();
            final Map<String, List<TSNode>> captures = new HashMap<>();
            for (final TSQueryCapture capture : match.getCaptures()) {
                final TSNode node = capture.getNode();
                switch (query.getCaptureNameForId(capture.getIndex())) {
                    case "class" -> { d.kind = ElementKind.CLASS; d.node = node; }
                    case "scope" -> { d.kind = ElementKind.CLASS; d.node = node; d.scope = true; }
                    case "method" -> { d.kind = ElementKind.METHOD; d.node = node; }
                    case "field" -> { d.kind = ElementKind.FIELD; d.node = node; }
                    case "body" -> d.endNode = node;
                    default -> captures.computeIfAbsent(query.getCaptureNameForId(capture.getIndex()),
                            x -> new ArrayList<>()).add(node);
                }
            }
            if (d.node == null || d.node.isError() || d.node.isMissing()) {
                continue; // skip only a match that is itself a recovery artifact; a well-formed
                          // declaration is kept even when a preprocessor-split construct elsewhere
                          // left it under a spanning ERROR node (tree-sitter still parses it correctly)
            }
            d.captures = new Captures(captures);
            d.start = d.node.getStartByte();
            d.end = d.node.getEndByte();
            final TSNode nameNode = d.captures.get("name");
            d.nameStart = nameNode == null ? d.start : nameNode.getStartByte();
            final String key = d.kind + ":" + d.start + ":" + d.end + ":"
                    + (nameNode == null ? "" : nameNode.getStartByte());
            if (seen.add(key)) {
                out.add(d);
            }
        }
        return out;
    }

    private void build(final List<Detected> found) {
        final Deque<Frame> stack = new ArrayDeque<>();
        for (final Detected d : found) {
            // pop frames that do not strictly enclose d; an element sharing d's exact node (another
            // name in the same declaration) is a sibling, not an ancestor, so pop it too
            while (!stack.isEmpty() && (stack.peek().detected.end <= d.start
                    || (stack.peek().detected.start == d.start && stack.peek().detected.end == d.end))) {
                stack.pop();
            }
            final ElementKind kind = refineKind(d.kind, d.node, d.captures);
            final Frame parent = stack.peek();
            // only a class or scope has members: anything nested under a method or field (a class local
            // to a method body, or the fields of an inline struct member) is dropped; a match the
            // subclass rejects is dropped too, and it drops its descendants like any dropped scope
            final boolean dropped = !accept(kind, d.node, d.captures) || (parent != null && (parent.dropped
                    || parent.kind == ElementKind.METHOD || parent.kind == ElementKind.FIELD));
            Element element = null;
            if (!dropped) {
                final Element parentElement = parent == null ? root : parent.element;
                final String label = name(kind, d.node, d.captures);
                if (d.scope) {
                    element = element(kind, label, parentElement, (TSNode) null);
                } else if (d.endNode != null) {
                    element = element(kind, label, parentElement, d.node, d.endNode);
                } else {
                    element = element(kind, label, parentElement, contentNode(d.node));
                }
            }
            stack.push(new Frame(d, kind, element, dropped));
        }
    }

    /**
     * The naming handles captured alongside an element.
     */
    protected static final class Captures {
        private final Map<String, List<TSNode>> map;

        private Captures(final Map<String, List<TSNode>> map) {
            this.map = map;
        }

        public TSNode get(final String name) {
            final List<TSNode> nodes = map.get(name);
            return nodes == null || nodes.isEmpty() ? null : nodes.get(0);
        }

        public List<TSNode> all(final String name) {
            return map.getOrDefault(name, List.of());
        }

        public boolean has(final String name) {
            return map.containsKey(name);
        }
    }

    private static final class Detected {
        private ElementKind kind;

        private TSNode node;

        private TSNode endNode;

        private boolean scope;

        private int start;

        private int end;

        private int nameStart;

        private Captures captures;
    }

    private static final class Frame {
        private final Detected detected;

        private final ElementKind kind;

        private final Element element;

        private final boolean dropped;

        private Frame(final Detected detected, final ElementKind kind, final Element element, final boolean dropped) {
            this.detected = detected;
            this.kind = kind;
            this.element = element;
            this.dropped = dropped;
        }
    }
}
