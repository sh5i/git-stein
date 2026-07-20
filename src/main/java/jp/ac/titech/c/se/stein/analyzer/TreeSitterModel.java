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
import jp.ac.titech.c.se.stein.core.SourceText.Fragment;
import jp.ac.titech.c.se.stein.util.Names;

/**
 * The per-file tree-sitter model: the decoded text, the parsed CST, and the element tree the
 * analyzer's detection engine populated, with each element's classified leaf-token stream recoverable
 * ({@link #getTokens}). Each token's type is derived from the grammar alone (structural tokens are typed
 * by the non-terminal that contains them, identifiers by the field they fill). Language-specific
 * decisions are not made here: they are delegated to the {@link QueryAnalyzer} that produced this
 * model.
 */
public final class TreeSitterModel implements TokenizingModel {
    private final QueryAnalyzer language;

    private final SourceText text;

    private final Element root;

    // the native node each element was extracted from, kept off the (backend-neutral) Element itself;
    // resolved on demand by nodeOf for tokenizing and language rules
    private final Map<Element, TSNode> nodes = new HashMap<>();

    TreeSitterModel(final QueryAnalyzer language, final String filename, final SourceText text,
                    final TSNode treeRoot) {
        this.language = language;
        this.text = text;
        this.root = new Element(Element.Kind.FILE, baseName(filename));
        final Fragment whole = exactFragment(treeRoot);
        root.setCoreFragment(whole);
        root.setExtentFragment(whole);
        nodes.put(root, treeRoot);
    }

    @Override
    public Element getRoot() {
        return root;
    }

    /**
     * The comments within the element's extent, in source order, walked straight off the CST the same
     * way {@link #getTokens} walks its leaves. Passing the file root yields every comment in the file.
     */
    @Override
    public List<Fragment> getExtentComments(final Element e) {
        final Fragment extent = e.getExtentFragment();
        if (extent == null) {
            return List.of();  // a naming scope has no source of its own
        }
        final List<Fragment> out = new ArrayList<>();
        collectComments(covering(nodeOf(e), extent.getBegin(), extent.getEnd()), extent.getBegin(), extent.getEnd(), out);
        return out;
    }

    /**
     * Collects into {@code out}, in source order, the comment nodes under {@code node} that begin within
     * {@code [begin, end)}.
     */
    private void collectComments(final TSNode node, final int begin, final int end, final List<Fragment> out) {
        if (text.toCharIndex(node.getEndByte()) <= begin || text.toCharIndex(node.getStartByte()) >= end) {
            return;  // entirely outside the range
        }
        if (language.isComment(node)) {
            if (text.toCharIndex(node.getStartByte()) >= begin) {
                out.add(exactFragment(node));
            }
            return;  // a comment's own children are not separate comments
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            collectComments(node.getChild(i), begin, end, out);
        }
    }

    /**
     * The nearest ancestor of {@code node} (or {@code node} itself) whose span covers {@code [begin, end)}.
     */
    private TSNode covering(final TSNode node, final int begin, final int end) {
        TSNode cover = node;
        while (text.toCharIndex(cover.getStartByte()) > begin || text.toCharIndex(cover.getEndByte()) < end) {
            final TSNode parent = cover.getParent();
            if (parent == null || parent.isNull()) {
                break;
            }
            cover = parent;
        }
        return cover;
    }

    /**
     * Adds an element of the given kind and name under the parent, and returns it (so the engine can
     * nest members under it). A null {@code content} node marks a naming scope that is never rendered.
     */
    Element element(final Element.Kind kind, final Signature signature, final Element parent, final TSNode content) {
        final Element e = new Element(kind, signature);
        if (content != null) {
            nodes.put(e, content);
            final Fragment core = language.coreFragment(this, content);
            final List<CommentAttachment.Node> comments = CommentAttachment.attached(new Sibling(content));
            e.setCoreFragment(core);
            e.setExtentFragment(extentOf(core, comments));
        }
        parent.addChild(e);
        return e;
    }

    /**
     * Adds an element that spans two adjacent nodes {@code [start .. end]}, for grammars that split a
     * declaration into separate sibling nodes (e.g. Dart's method signature and body).
     */
    Element element(final Element.Kind kind, final Signature signature, final Element parent,
                    final TSNode start, final TSNode end) {
        final Element e = new Element(kind, signature);
        nodes.put(e, start);
        final Fragment core = text.getFragment(text.toCharIndex(start.getStartByte()), text.toCharIndex(end.getEndByte()));
        final List<CommentAttachment.Node> comments = CommentAttachment.attached(new Sibling(start));
        e.setCoreFragment(core);
        e.setExtentFragment(extentOf(core, comments));
        parent.addChild(e);
        return e;
    }

    /**
     * The tree-sitter node an element was extracted from. The association is kept here rather than on
     * the (backend-neutral) {@link Element}, so a consumer never sees a tree-sitter type.
     */
    public TSNode nodeOf(final Element e) {
        return nodes.get(e);
    }

    /**
     * Walks the whole file's token stream, wrapping each extracted class/method/field element (by its
     * source range) in a {@link TokenVisitor#begin}/{@link TokenVisitor#end} pair. Naming scopes with no
     * source region of their own (e.g. namespaces) are not wrapped, but every token is still emitted.
     */
    @Override
    public void walkTokens(final TokenVisitor sink) {
        final List<Region> regions = new ArrayList<>();
        collectRegions(root, regions, new HashSet<>());
        // outermost first at the same start, so nesting opens correctly
        regions.sort((x, y) -> x.start() != y.start() ? Integer.compare(x.start(), y.start())
                : Integer.compare(y.end(), x.end()));
        final Deque<Region> open = new ArrayDeque<>();
        int r = 0;
        for (final Token t : getTokens(root)) {
            final int p = t.start();
            while (!open.isEmpty() && open.peek().end() <= p) {
                sink.end(open.pop().element());
            }
            while (r < regions.size() && regions.get(r).start() <= p) {
                final Region region = regions.get(r++);
                if (region.end() > p) {
                    sink.begin(region.element());
                    open.push(region);
                }
            }
            sink.token(t);
        }
        while (!open.isEmpty()) {
            sink.end(open.pop().element());
        }
    }

    /**
     * An element's source range, paired with the element itself so a marker can name it.
     */
    private record Region(int start, int end, Element element) {
    }

    private void collectRegions(final Element e, final List<Region> regions, final Set<Long> seen) {
        for (final Element c : e.getChildren()) {
            if (c.hasContent()) {
                final int start = c.getCoreFragment().getBegin();
                final int end = c.getCoreFragment().getEnd();
                if (seen.add((long) start << 32 | (end & 0xffffffffL))) {
                    regions.add(new Region(start, end, c));
                }
            }
            collectRegions(c, regions, seen);
        }
    }

    /**
     * The leaf-token stream of an element, in source order, each typed by {@link #category} and flagged
     * with its {@link Token#comment} and {@link Token#frame} classification. Comments are kept (flagged,
     * not dropped) so a consumer chooses; passing the file root yields the whole file's tokens. The
     * stream covers the element's own source range ({@link Element#getCoreFragment}), so it agrees with
     * the rendered text even where a language widens the range beyond the detected node (e.g. C++'s
     * template header). Frame delimiters are flagged relative to the element's declaration node.
     */
    @Override
    public List<Token> getTokens(final Element e) {
        final List<Token> out = new ArrayList<>();
        final TSNode node = nodeOf(e);
        if (node == null) {
            return out;  // a naming scope has no source of its own
        }
        final int begin = e.getCoreFragment().getBegin();
        final int end = e.getCoreFragment().getEnd();
        // walk from the nearest ancestor covering the whole range (the node itself, usually)
        collectTokens(covering(node, begin, end), frameOf(node), false, begin, end, out);
        return out;
    }

    /**
     * Adapts a tree-sitter node to the backend-neutral {@link CommentAttachment.Node} the attachment rule walks.
     */
    private final class Sibling implements CommentAttachment.Node {
        private final TSNode node;

        Sibling(final TSNode node) {
            this.node = node;
        }

        @Override
        public CommentAttachment.Node previous() {
            final TSNode p = node.getPrevSibling();
            return p.isNull() ? null : new Sibling(p);
        }

        @Override
        public CommentAttachment.Node next() {
            final TSNode n = node.getNextSibling();
            return n.isNull() ? null : new Sibling(n);
        }

        @Override
        public int startRow() {
            return node.getStartPoint().getRow();
        }

        @Override
        public int endRow() {
            return node.getEndPoint().getRow();
        }

        @Override
        public boolean isComment() {
            return language.isComment(node);
        }

        @Override
        public boolean isDocComment() {
            return language.isDocComment(TreeSitterModel.this, node);
        }

        @Override
        public boolean isNamed() {
            return node.isNamed();
        }

        @Override
        public Fragment fragment() {
            return exactFragment(node);
        }
    }

    private Fragment extentOf(final Fragment core, final List<CommentAttachment.Node> comments) {
        int start = core.getBegin();
        int end = core.getEnd();
        for (final CommentAttachment.Node c : comments) {
            start = Math.min(start, c.fragment().getBegin());
            end = Math.max(end, c.fragment().getEnd());
        }
        return text.getFragment(start, end);
    }

    private void collectTokens(final TSNode node, final Frame frame, final boolean inComment,
                               final int begin, final int end, final List<Token> out) {
        if (text.toCharIndex(node.getEndByte()) <= begin || text.toCharIndex(node.getStartByte()) >= end) {
            return; // entirely outside the element's range
        }
        // most grammars mark a comment as an extra node, but some (Rust, Dart) model it as a named
        // *comment node whose punctuation leaves are not themselves extra, so comment-ness propagates down
        final boolean comment = inComment || node.isExtra() || language.isComment(node);
        if (node.getChildCount() > 0) {
            int pos = text.toCharIndex(node.getStartByte());
            for (int i = 0; i < node.getChildCount(); i++) {
                final TSNode child = node.getChild(i);
                collectGap(node, pos, text.toCharIndex(child.getStartByte()), comment, begin, end, out);
                collectTokens(child, frame, comment, begin, end, out);
                pos = text.toCharIndex(child.getEndByte());
            }
            collectGap(node, pos, text.toCharIndex(node.getEndByte()), comment, begin, end, out);
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
     * Emits whatever source the grammar left between a node's children, so that no character reaches the
     * stream only by luck of the grammar. A lexer may swallow a character without ever giving it a node
     * -- a line continuation is the usual case, and tree-sitter drops it as inter-token whitespace in
     * every language that has one -- and the file is owed it all the same: a consumer walking the token
     * stream against the original source loses step at the first character that never arrives. Since the
     * text has no node of its own, it is typed by the node enclosing it.
     */
    private void collectGap(final TSNode parent, final int from, final int to, final boolean comment,
                            final int begin, final int end, final List<Token> out) {
        final String content = text.getContent();
        int start = from;
        while (start < to && Character.isWhitespace(content.charAt(start))) {
            start++;
        }
        int stop = to;
        while (stop > start && Character.isWhitespace(content.charAt(stop - 1))) {
            stop--;
        }
        if (start >= stop || start < begin || start >= end) {
            return;
        }
        final String gap = content.substring(start, stop).replaceAll("[\\r\\n]+", " ");
        final int line = text.getFragment(start, stop).getBeginLine();
        final int column = start - text.getFragmentOfLines(line, line).getBegin() + 1;
        out.add(new Token(gap, gapCategory(parent, gap), line, column, start, comment, false));
    }

    /**
     * The type of a gap token: the symbol name of the text it holds, in the context of the node that
     * encloses it, following the typing {@link #category} gives a leaf.
     */
    private String gapCategory(final TSNode parent, final String gap) {
        final String context = parent.getType().toUpperCase(Locale.ROOT);
        final String symbol = symbolName(gap);
        return symbol == null ? context : context + "_" + symbol;
    }

    /**
     * The frame context of a declaration node: its parameter-list and body nodes (each possibly a null
     * node) and whether it is a function, against which a leaf is tested by {@link #isFrame}.
     */
    private record Frame(TSNode parameters, TSNode body, TSNode root, boolean function) {
    }

    private Frame frameOf(final TSNode declaration) {
        return new Frame(declaration.getChildByFieldName("parameters"), declaration.getChildByFieldName("body"),
                declaration, language.isFunctionNode(declaration));
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

    /**
     * The source text of the node, or the empty string for a null node.
     */
    public String textOf(final TSNode node) {
        if (node.isNull()) {
            return "";
        }
        return text.getContent().substring(text.toCharIndex(node.getStartByte()), text.toCharIndex(node.getEndByte()));
    }

    /**
     * The fragment covering the node's exact span.
     */
    public Fragment exactFragment(final TSNode node) {
        return text.getFragment(text.toCharIndex(node.getStartByte()), text.toCharIndex(node.getEndByte()));
    }

    /**
     * The decoded source text, for a language rule that needs raw content or custom fragments beyond
     * {@link #textOf} and {@link #exactFragment}.
     */
    public SourceText getText() {
        return text;
    }

    /**
     * Escapes a source name into a file-system-safe component (white space and reserved characters).
     */
    public String escape(final String name) {
        return Names.escape(name);
    }

    /**
     * Flattens a possibly qualified name into a file-system-safe leaf, turning the {@code ::} scope
     * operator into {@code .} and escaping the remaining reserved characters.
     */
    public String flatten(final String name) {
        return Names.escape(name.replace("::", "."));
    }

    /**
     * The first named child of the given type, or null if there is none.
     */
    public TSNode firstChildOfType(final TSNode node, final String type) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            if (child.getType().equals(type)) {
                return child;
            }
        }
        return null;
    }

    /**
     * Reparents this model's elements that start at or after character offset {@code from} under a new
     * scope appended to the given parent; used by a {@link QueryAnalyzer#postProcess} hook to realize a
     * file-scoped namespace.
     */
    public Element reparentAfter(final Element parent, final String scopeName, final int from) {
        final Element scope = new Element(Element.Kind.CLASS, scopeName);
        final List<Element> children = parent.getChildren();
        final List<Element> moved = new ArrayList<>();
        children.removeIf(e -> {
            if (e.hasContent() && e.getCoreFragment().getBegin() >= from) {
                moved.add(e);
                return true;
            }
            return false;
        });
        moved.forEach(scope::addChild);
        parent.addChild(scope);
        return scope;
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
