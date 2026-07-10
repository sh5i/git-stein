package jp.ac.titech.c.se.stein.analyzer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.treesitter.TSNode;
import org.treesitter.TSPoint;

import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.util.Names;

/**
 * The shared skeleton of a per-language tree-sitter analyzer. A subclass walks its language's CST in
 * {@link #run} and emits {@link Element} instances via {@link #element}; this base recovers an
 * element's raw source ({@link #rawText}) and its classified leaf-token stream ({@link #tokens}). Each
 * token's type is derived from the grammar alone (structural tokens are typed by the non-terminal that
 * contains them, identifiers by the field they fill), so it needs no per-language rules.
 */
public abstract class TreeSitterAnalyzer implements TokenizingAnalyzer {
    protected final String filename;

    protected final SourceText text;

    protected final TSNode treeRoot;

    protected final Element root;

    // the native node each element was extracted from, kept off the (backend-neutral) Element itself;
    // resolved on demand by nodeOf/endNodeOf for rendering and tokenizing
    private final Map<Element, TSNode> nodes = new HashMap<>();

    private final Map<Element, TSNode> endNodes = new HashMap<>();

    protected TreeSitterAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        this.filename = filename;
        this.text = text;
        this.treeRoot = treeRoot;
        this.root = new Element(Element.Kind.FILE, baseName(filename),
                text.toCharIndex(treeRoot.getStartByte()), text.toCharIndex(treeRoot.getEndByte()));
        nodes.put(root, treeRoot);
    }

    /**
     * Extracts the element tree: a {@link Element.Kind#FILE} root holding the file's declarations.
     */
    @Override
    public Element extract() {
        run();
        return root;
    }

    /**
     * Walks {@link #treeRoot} and populates {@link #root} with the extracted elements.
     */
    protected abstract void run();

    /**
     * Adds an element of the given kind and name under the parent, and returns it (so a caller can
     * nest members under it). A null {@code content} node marks a naming scope that is never rendered.
     */
    protected Element element(final Element.Kind kind, final Signature signature, final Element parent, final TSNode content) {
        final Element e = content == null ? new Element(kind, signature)
                : new Element(kind, signature, text.toCharIndex(content.getStartByte()), text.toCharIndex(content.getEndByte()));
        if (content != null) {
            nodes.put(e, content);
            e.setStartLine(content.getStartPoint().getRow() + 1);
            e.setEndLine(content.getEndPoint().getRow() + 1);
        }
        parent.addChild(e);
        return e;
    }

    /**
     * Adds an element that spans two adjacent nodes {@code [start .. end]}, for grammars that split a
     * declaration into separate sibling nodes (e.g. Dart's method signature and body).
     */
    protected Element element(final Element.Kind kind, final Signature signature, final Element parent,
                              final TSNode start, final TSNode end) {
        final Element e = new Element(kind, signature,
                text.toCharIndex(start.getStartByte()), text.toCharIndex(start.getEndByte()),
                text.toCharIndex(end.getStartByte()), text.toCharIndex(end.getEndByte()));
        nodes.put(e, start);
        endNodes.put(e, end);
        e.setStartLine(start.getStartPoint().getRow() + 1);
        e.setEndLine(end.getEndPoint().getRow() + 1);
        parent.addChild(e);
        return e;
    }

    /**
     * The tree-sitter node an element was extracted from. The association is kept here rather than on
     * the (backend-neutral) {@link Element}, so a consumer never sees a tree-sitter type.
     */
    protected TSNode nodeOf(final Element e) {
        return nodes.get(e);
    }

    private TSNode endNodeOf(final Element e) {
        return endNodes.get(e);
    }

    /**
     * The raw source text of an element: the source lines its declaration spans (both segments when it
     * spans split nodes).
     */
    @Override
    public String rawText(final Element e) {
        if (!e.hasSpan()) {
            return rawContentOf(nodeOf(e));
        }
        final int begin = nodeOf(e).getStartPoint().getRow() + 1;
        final int end = endNodeOf(e).getEndPoint().getRow() + 1;
        return text.getFragmentOfLines(begin, end).getWiderContent();
    }

    /**
     * Walks the whole file's token stream, wrapping each extracted class/method/field element (by its
     * source range) in a {@link TokenVisitor#begin}/{@link TokenVisitor#end} pair. Naming scopes with no
     * source region of their own (e.g. namespaces) are not wrapped, but every token is still emitted.
     */
    @Override
    public void walkTokens(final TokenVisitor sink) {
        final List<int[]> regions = new ArrayList<>();
        collectRegions(root, regions, new HashSet<>());
        // outermost first at the same start, so nesting opens correctly
        regions.sort((x, y) -> x[0] != y[0] ? Integer.compare(x[0], y[0]) : Integer.compare(y[1], x[1]));
        final Deque<int[]> open = new ArrayDeque<>();
        int r = 0;
        for (final Token t : tokens(root)) {
            final int p = t.start();
            while (!open.isEmpty() && open.peek()[1] <= p) {
                sink.end(Element.Kind.values()[open.pop()[2]]);
            }
            while (r < regions.size() && regions.get(r)[0] <= p) {
                final int[] region = regions.get(r++);
                if (region[1] > p) {
                    sink.begin(Element.Kind.values()[region[2]]);
                    open.push(region);
                }
            }
            sink.token(t);
        }
        while (!open.isEmpty()) {
            sink.end(Element.Kind.values()[open.pop()[2]]);
        }
    }

    private void collectRegions(final Element e, final List<int[]> regions, final Set<Long> seen) {
        for (final Element c : e.getChildren()) {
            if (c.hasContent()) {
                final int start = c.getStart();
                final int end = c.getEnd();
                if (seen.add((long) start << 32 | (end & 0xffffffffL))) {
                    regions.add(new int[] {start, end, c.getKind().ordinal()});
                }
            }
            collectRegions(c, regions, seen);
        }
    }

    /**
     * The leaf-token stream of an element, in source order, each typed by {@link #category} and flagged
     * with its {@link Token#comment} and {@link Token#frame} classification. Comments are kept (flagged,
     * not dropped) so a consumer chooses; passing the file root yields the whole file's tokens. Frame
     * delimiters are flagged relative to the element's own declaration; a split-node element flags none.
     */
    @Override
    public List<Token> tokens(final Element e) {
        final List<Token> out = new ArrayList<>();
        if (e.hasSpan()) {
            collectTokens(nodeOf(e), null, false, out);
            collectTokens(endNodeOf(e), null, false, out);
        } else {
            collectTokens(nodeOf(e), frameOf(nodeOf(e)), false, out);
        }
        return out;
    }

    /**
     * The comments attached to an element's declaration, for a Historage comment side file: the run of
     * comment siblings directly above it (stopping at a blank line) and a comment trailing on the same
     * line as its end, each on its own line. Returns the empty string when there are none.
     */
    @Override
    public String commentText(final Element e) {
        final TSNode node = nodeOf(e);
        final StringBuilder sb = new StringBuilder();
        final List<TSNode> leading = new ArrayList<>();
        int topRow = node.getStartPoint().getRow();
        for (TSNode p = node.getPrevSibling(); !p.isNull() && isComment(p); p = p.getPrevSibling()) {
            if (p.getEndPoint().getRow() + 1 < topRow) {
                break; // a blank line separates this comment from the declaration below it
            }
            leading.add(p);
            topRow = p.getStartPoint().getRow();
        }
        for (int i = leading.size() - 1; i >= 0; i--) {
            sb.append(textOf(leading.get(i))).append("\n");
        }
        final TSNode next = node.getNextSibling();
        if (!next.isNull() && isComment(next) && next.getStartPoint().getRow() == node.getEndPoint().getRow()) {
            sb.append(textOf(next)).append("\n");
        }
        return sb.toString();
    }

    /**
     * Whether the node is a comment. The generic test matches any node type ending in {@code comment}
     * (e.g. {@code line_comment}, {@code block_comment}, {@code comment}); a subclass may narrow it.
     */
    protected boolean isComment(final TSNode node) {
        return node.getType().endsWith("comment");
    }

    private void collectTokens(final TSNode node, final Frame frame, final boolean inComment, final List<Token> out) {
        // most grammars mark a comment as an extra node, but some (Rust, Dart) model it as a named
        // *comment node whose punctuation leaves are not themselves extra, so comment-ness propagates down
        final boolean comment = inComment || node.isExtra() || isComment(node);
        if (node.getChildCount() > 0) {
            for (int i = 0; i < node.getChildCount(); i++) {
                collectTokens(node.getChild(i), frame, comment, out);
            }
            return;
        }
        if (node.isMissing()) {
            return; // an inserted error-recovery token is not real source
        }
        final String text = textOf(node).replaceAll("[\\r\\n]+", " ").trim();
        if (text.isEmpty()) {
            return;
        }
        final TSPoint start = node.getStartPoint();
        out.add(new Token(text, category(node), start.getRow() + 1, start.getColumn() + 1,
                this.text.toCharIndex(node.getStartByte()), comment, frame != null && isFrame(node, frame)));
    }

    /**
     * The frame context of a declaration node: its parameter-list and body nodes (each possibly a null
     * node) and whether it is a function, against which a leaf is tested by {@link #isFrame}.
     */
    private record Frame(TSNode parameters, TSNode body, TSNode root, boolean function) {
    }

    private Frame frameOf(final TSNode declaration) {
        return new Frame(declaration.getChildByFieldName("parameters"), declaration.getChildByFieldName("body"),
                declaration, isFunctionNode(declaration));
    }

    /**
     * Whether the leaf is one of the declaration's frame delimiters (Heuristic 2): the parentheses of
     * its parameter list, the braces of its body, or a bodyless declaration's terminating semicolon.
     */
    private boolean isFrame(final TSNode leaf, final Frame frame) {
        final TSNode parent = leaf.getParent();
        return switch (leaf.getType()) {
            case "(", ")" -> !frame.parameters().isNull() && sameNode(parent, frame.parameters());
            case "{", "}" -> !frame.body().isNull() && sameNode(parent, frame.body());
            case ";" -> frame.function() && sameNode(parent, frame.root());
            default -> false;
        };
    }

    protected String textOf(final TSNode node) {
        if (node.isNull()) {
            return "";
        }
        return text.getContent().substring(text.toCharIndex(node.getStartByte()), text.toCharIndex(node.getEndByte()));
    }

    /**
     * Escapes a source name into a file-system-safe component (white space and reserved characters).
     */
    protected String escape(final String name) {
        return Names.escape(name);
    }

    /**
     * Flattens a possibly qualified name into a file-system-safe leaf, turning the {@code ::} scope
     * operator into {@code .} and escaping the remaining reserved characters.
     */
    protected String flatten(final String name) {
        return Names.escape(name.replace("::", "."));
    }

    /**
     * The first named child of the given type, or null if there is none.
     */
    protected TSNode firstChildOfType(final TSNode node, final String type) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            if (child.getType().equals(type)) {
                return child;
            }
        }
        return null;
    }

    /**
     * The raw source of an element: the full source lines spanning the node. Subclasses override
     * this with language-specific extraction (e.g. attaching comments).
     */
    protected String rawContentOf(final TSNode node) {
        final int beginLine = node.getStartPoint().getRow() + 1;
        final int endLine = node.getEndPoint().getRow() + 1;
        return text.getFragmentOfLines(beginLine, endLine).getWiderContent();
    }

    /**
     * Whether the node declares a function/method (so its terminating semicolon, if any, is a frame
     * token). The generic answer is no; language subclasses override it.
     */
    protected boolean isFunctionNode(final TSNode node) {
        return false;
    }

    /**
     * The FinerGit token type. A punctuation or operator token is refined with its syntactic context
     * (Heuristic 1): its type is {@code <context>_<symbol-name>}, where a wrapper node ({@code block},
     * {@code statement_block}, {@code compound_statement}, {@code parenthesized_expression}) yields to
     * the enclosing statement, so a method body brace and an {@code if} block brace get distinct types,
     * and an {@code if} condition operator differs from a return-value one. Every other token defers to
     * {@link #tokenType}.
     */
    protected String category(final TSNode leaf) {
        final String symbol = symbolName(leaf.getType());
        if (symbol == null) {
            return tokenType(leaf);
        }
        final TSNode parent = leaf.getParent();
        final String context = isWrapper(parent.getType()) ? parent.getParent().getType() : parent.getType();
        return context.toUpperCase(Locale.ROOT) + "_" + symbol;
    }

    /**
     * The name of a punctuation or operator token, each character spelled out (so {@code ==} becomes
     * {@code EQEQ} and {@code ->} becomes {@code MINUSGT}), or null when the token is not pure
     * punctuation (a keyword, identifier, or literal).
     */
    protected String symbolName(final String type) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < type.length(); i++) {
            final String word = switch (type.charAt(i)) {
                case '(' -> "LPAREN";
                case ')' -> "RPAREN";
                case '{' -> "LBRACE";
                case '}' -> "RBRACE";
                case '[' -> "LBRACKET";
                case ']' -> "RBRACKET";
                case ';' -> "SEMICOLON";
                case ',' -> "COMMA";
                case '.' -> "DOT";
                case ':' -> "COLON";
                case '?' -> "QUESTION";
                case '+' -> "PLUS";
                case '-' -> "MINUS";
                case '*' -> "STAR";
                case '/' -> "SLASH";
                case '%' -> "PERCENT";
                case '=' -> "EQ";
                case '<' -> "LT";
                case '>' -> "GT";
                case '!' -> "BANG";
                case '&' -> "AMP";
                case '|' -> "PIPE";
                case '^' -> "CARET";
                case '~' -> "TILDE";
                case '@' -> "AT";
                case '#' -> "HASH";
                case '$' -> "DOLLAR";
                case '\\' -> "BACKSLASH";
                case '`' -> "BACKTICK";
                default -> null;
            };
            if (word == null) {
                return null;
            }
            sb.append(word);
        }
        return sb.isEmpty() ? null : sb.toString();
    }

    /**
     * Whether the node is a generic wrapper whose role comes from its parent (e.g. the block a method
     * body and an {@code if} body share). Language subclasses may extend the set.
     */
    protected boolean isWrapper(final String type) {
        return switch (type) {
            case "block", "statement_block", "compound_statement", "parenthesized_expression" -> true;
            default -> false;
        };
    }

    /**
     * The type of a non-structural token, elevated from the non-terminal that contains it (the same
     * principle as the structural tokens). An identifier that names a declaration or a callee is
     * elevated to its grammatical position {@code <parent-non-terminal>_<field>} (e.g. a {@code name}
     * field of a method declaration, or the {@code function} of a call); every other identifier is a
     * plain variable name, kept uniform so that moving it does not break tracking. A type reference
     * becomes a type name; a keyword, operator, or literal keeps its node type. This is fully
     * grammar-derived, so it needs no per-language rules.
     */
    protected String tokenType(final TSNode leaf) {
        final String type = leaf.getType();
        if (type.equals("type_identifier")) {
            return "TYPE_NAME";
        }
        if (isNameLeaf(type)) {
            final TSNode parent = leaf.getParent();
            final String field = fieldName(parent, leaf);
            if (field.equals("name") || field.equals("function")) {
                return parent.getType().toUpperCase(Locale.ROOT) + "_" + field.toUpperCase(Locale.ROOT);
            }
            return "VARIABLE_NAME";
        }
        return type.toUpperCase(Locale.ROOT);
    }

    protected boolean isNameLeaf(final String type) {
        return switch (type) {
            case "identifier", "field_identifier", "property_identifier", "shorthand_property_identifier",
                 "simple_identifier", "constant" -> true;
            default -> false;
        };
    }

    /**
     * The field name the given child fills in its parent, or the empty string if none.
     */
    protected String fieldName(final TSNode parent, final TSNode child) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            final TSNode c = parent.getChild(i);
            if (c.getStartByte() == child.getStartByte() && c.getEndByte() == child.getEndByte()) {
                final String field = parent.getFieldNameForChild(i);
                return field != null ? field : "";
            }
        }
        return "";
    }

    protected boolean sameNode(final TSNode a, final TSNode b) {
        return a.getStartByte() == b.getStartByte() && a.getEndByte() == b.getEndByte();
    }

    /**
     * The source file name without its extension, used as the name of the file root.
     */
    protected static String baseName(final String filename) {
        final int index = filename.lastIndexOf('.');
        return index > 0 ? filename.substring(0, index) : filename;
    }
}
