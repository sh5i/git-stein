package jp.ac.titech.c.se.stein.analyzer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;
import org.treesitter.TSQueryCapture;
import org.treesitter.TSQueryCursor;
import org.treesitter.TSQueryMatch;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.core.SourceText.Fragment;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import lombok.extern.slf4j.Slf4j;

/**
 * One language's tree-sitter analyzer: it owns the grammar, the element-detection query written
 * against it, the filename filter, the source decoder, and the language's interpretation rules
 * (naming, kind refinement, comment recognition, ...). Analyzing a file parses it, runs the
 * <em>detection engine</em> over the tree, and returns the populated {@link TreeSitterModel}.
 *
 * <p>Detection is declarative: the query captures each element node (tagged {@code @class},
 * {@code @method}, {@code @field}, or {@code @scope}) together with naming handles ({@code @name},
 * {@code @params}, {@code @receiver}, ...) and, for grammars that split a declaration, an adjacent
 * body ({@code @body}, which makes the element span the two). The engine runs the query, rebuilds the
 * element tree from the captures by source containment, and asks the language subclass only to
 * <em>name</em> each element. Two extraction rules a query cannot express are handled generically: an
 * element whose nearest enclosing element is a method is dropped (a method has no members, so a class
 * local to a method body is not extracted, while one in an initializer block — which is not a captured
 * element — survives under its enclosing class); and members nest under the innermost enclosing
 * captured element. A stateful case a query still cannot handle (a file-scoped namespace that scopes
 * its later siblings) is left to the {@link #postProcess} hook.</p>
 *
 * <p>Parsers are held per thread, since tree-sitter parsers are not thread-safe; the query is compiled
 * once on first use.</p>
 */
@Slf4j
public abstract class QueryAnalyzer implements Analyzer.Tokenizing {
    private final String name;

    private final NameFilter filter;

    private final Function<byte[], String> decoder;

    private final Supplier<TSLanguage> language;

    private final String queryString;

    private final ThreadLocal<TSParser> parser;

    private TSQuery query;

    protected QueryAnalyzer(final String name, final NameFilter filter, final Function<byte[], String> decoder,
                            final Supplier<TSLanguage> language, final String queryString) {
        this.name = name;
        this.filter = filter;
        this.decoder = decoder;
        this.language = language;
        this.queryString = queryString;
        this.parser = ThreadLocal.withInitial(() -> {
            final TSParser p = new TSParser();
            p.setLanguage(language.get());
            return p;
        });
    }

    @Override
    public boolean accepts(final String filename) {
        return filter.accept(filename);
    }

    @Override
    public String languageName(final String filename) {
        return name;
    }

    @Override
    public TokenizingModel analyze(final String filename, final byte[] blob, final Context c) {
        final SourceText text = SourceText.ofNormalized(blob, decoder);
        final TSNode treeRoot = parser.get().parseString(null, text.getContent()).getRootNode();
        if (treeRoot.hasError()) {
            log.debug("Syntax errors found; extracting the elements that parsed");
        }
        final TreeSitterModel m = new TreeSitterModel(this, filename, text, treeRoot);
        extract(m, treeRoot);
        return m;
    }

    /**
     * The compiled element-detection query, shared by every model this analyzer produces.
     */
    protected synchronized TSQuery query() {
        if (query == null) {
            query = new TSQuery(language.get(), queryString);
        }
        return query;
    }

    // --- the detection engine ---

    /**
     * Populates the model's element tree from the query's captures.
     */
    private void extract(final TreeSitterModel m, final TSNode treeRoot) {
        final List<Detected> found = detect(treeRoot);
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
        build(m, found);
        postProcess(m, m.getRoot());
    }

    private List<Detected> detect(final TSNode treeRoot) {
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
                    case "class" -> { d.kind = Element.Kind.CLASS; d.node = node; }
                    case "scope" -> { d.kind = Element.Kind.CLASS; d.node = node; d.scope = true; }
                    case "method" -> { d.kind = Element.Kind.METHOD; d.node = node; }
                    case "field" -> { d.kind = Element.Kind.FIELD; d.node = node; }
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

    private void build(final TreeSitterModel m, final List<Detected> found) {
        final Deque<Frame> stack = new ArrayDeque<>();
        for (final Detected d : found) {
            // pop frames that do not strictly enclose d; an element sharing d's exact node (another
            // name in the same declaration) is a sibling, not an ancestor, so pop it too
            while (!stack.isEmpty() && (stack.peek().detected.end <= d.start
                    || (stack.peek().detected.start == d.start && stack.peek().detected.end == d.end))) {
                stack.pop();
            }
            final Element.Kind kind = refineKind(m, d.kind, d.node, d.captures);
            final Frame parent = stack.peek();
            // only a class or scope has members: anything nested under a method or field (a class local
            // to a method body, or the fields of an inline struct member) is dropped; a match the
            // language rejects is dropped too, and it drops its descendants like any dropped scope
            final boolean dropped = !accept(m, kind, d.node, d.captures) || (parent != null
                    && (parent.dropped || parent.kind == Element.Kind.METHOD || parent.kind == Element.Kind.FIELD));
            Element element = null;
            if (!dropped) {
                final Element parentElement = parent == null ? m.getRoot() : parent.element;
                final Signature sig = signature(m, kind, d.node, d.captures);
                if (d.scope) {
                    element = m.element(kind, sig, parentElement, (TSNode) null);
                } else if (d.endNode != null) {
                    element = m.element(kind, sig, parentElement, d.node, d.endNode);
                } else {
                    element = m.element(kind, sig, parentElement, contentNode(d.node));
                }
            }
            stack.push(new Frame(d, kind, element, dropped));
        }
    }

    private static final class Detected {
        private Element.Kind kind;

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

        private final Element.Kind kind;

        private final Element element;

        private final boolean dropped;

        private Frame(final Detected detected, final Element.Kind kind, final Element element, final boolean dropped) {
            this.detected = detected;
            this.kind = kind;
            this.element = element;
            this.dropped = dropped;
        }
    }

    // --- language rules, consulted by the models this analyzer produces ---

    /**
     * The naming material of a detected element, computed from its node and captured naming handles:
     * its name and, for a method, its type parameters and parameter list. The naming strategy composes
     * these into a leaf file name; the analyzer does not assemble the signature itself.
     */
    protected abstract Signature signature(TreeSitterModel m, Element.Kind kind, TSNode node, Captures captures);

    /**
     * Refines the kind a capture tag gave an element, for a language whose kind depends on a child a
     * query cannot condition on (e.g. a Go {@code type} spec is a class when it aliases a struct or
     * interface, otherwise a field). The default keeps the captured kind.
     */
    protected Element.Kind refineKind(final TreeSitterModel m, final Element.Kind kind, final TSNode node,
                                      final Captures captures) {
        return kind;
    }

    /**
     * Whether a detected element should be kept. A query captures by node type, but a language may need
     * to reject a match on a structural condition a query cannot express (e.g. a C++ field declarator
     * that unwraps to a function prototype rather than a data member). A rejected match is treated as a
     * dropped scope, so anything nested inside it is dropped too — the engine does not descend into a
     * skipped declaration. The default keeps every match.
     */
    protected boolean accept(final TreeSitterModel m, final Element.Kind kind, final TSNode node, final Captures captures) {
        return true;
    }

    /**
     * The node whose text becomes an element's content, given its detected node. The default is the
     * node itself; a language widens it to an enclosing wrapper treated as the element's extent (e.g.
     * JavaScript's {@code export_statement}, so an exported declaration's content includes the
     * {@code export} keyword and any leading decorators). Only the content is affected; nesting and
     * ordering still use the detected node.
     */
    protected TSNode contentNode(final TSNode node) {
        return node;
    }

    /**
     * A hook for the stateful scoping a query cannot express, run on the freshly built element tree.
     * The default does nothing; C# overrides it to move the siblings that follow a file-scoped
     * namespace under it.
     */
    protected void postProcess(final TreeSitterModel m, final Element root) {
    }

    /**
     * Whether the node is a comment. The generic test matches any node type ending in {@code comment}
     * (e.g. {@code line_comment}, {@code block_comment}, {@code comment}); a language may narrow it.
     */
    protected boolean isComment(final TSNode node) {
        return node.getType().endsWith("comment");
    }

    /**
     * Whether the comment is a documentation comment, which binds to the declaration directly below it
     * regardless of an intervening blank line. A {@code /**} block is a doc comment across the C family
     * (Javadoc, Doxygen, KDoc, JSDoc, PHPDoc, ...); a language may recognize more (e.g. a {@code ///}
     * line comment in C#, Rust, Swift, or Dart).
     */
    protected boolean isDocComment(final TreeSitterModel m, final TSNode node) {
        return m.textOf(node).startsWith("/**");
    }

    /**
     * The element's own source range: the node's exact span. A language narrows or widens it (e.g.
     * C++'s declaration terminator).
     */
    protected Fragment coreFragment(final TreeSitterModel m, final TSNode node) {
        return m.exactFragment(node);
    }

    /**
     * Whether the node declares a function/method (so its terminating semicolon, if any, is a frame
     * token). The generic answer is no; a language overrides it.
     */
    protected boolean isFunctionNode(final TSNode node) {
        return false;
    }

    /**
     * The naming handles captured alongside an element.
     */
    public static final class Captures {
        private final Map<String, List<TSNode>> map;

        Captures(final Map<String, List<TSNode>> map) {
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
}
