package jp.ac.titech.c.se.stein.analyzer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Node;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.core.SourceText.Fragment;
import jp.ac.titech.c.se.stein.util.ProcessRunner;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Option;

/**
 * The srcML analyzer: C, C++, C#, and Java, preprocessor-aware. It runs the {@code srcml}
 * executable (its own option) over each file and wraps the resulting {@code <unit>} DOM in a
 * {@link Model}. The source language may be forced by the consuming app, for file names srcML
 * would not map.
 */
@Slf4j
public class SrcmlAnalyzer implements Analyzer.Tokenizing {
    public static final String[] JAVA_EXT = {".java", ".aj", ".mjava", ".fjava", ".cjava"};
    public static final String[] C_EXT = {".c", ".h", ".i"};
    public static final String[] CXX_EXT = {".cpp", ".CPP", ".cp", ".hpp", ".cxx", ".hxx", ".cc", ".hh", ".c++", ".h++", ".C", ".H", ".tcc", ".ii"};
    public static final String[] CSHARP_EXT = {".cs"};

    @Getter
    @Setter
    @Option(names = "--srcml-cmd", description = "srcml command path")
    private String command = "srcml";

    @Setter
    private String language;

    /**
     * The srcML language for the given file name, or null when srcML does not handle it. Extension
     * lists follow srcML's own registry.
     */
    public static String languageOf(final String filename) {
        if (endsWithAny(filename, JAVA_EXT)) {
            return "Java";
        }
        if (endsWithAny(filename, C_EXT)) {
            return "C";
        }
        if (endsWithAny(filename, CXX_EXT)) {
            return "C++";
        }
        if (endsWithAny(filename, CSHARP_EXT)) {
            return "C#";
        }
        return null;
    }

    private static boolean endsWithAny(final String filename, final String[] extensions) {
        for (final String ext : extensions) {
            if (filename.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private Boolean available;

    /**
     * Whether the srcml executable is present (checked once); a missing command makes this analyzer
     * accept nothing, so an app falls through to its next analyzer.
     */
    private boolean available() {
        if (available == null) {
            available = ProcessRunner.isAvailable(command);
        }
        return available;
    }

    @Override
    public boolean accepts(final String filename) {
        return available() && (language != null || languageOf(filename) != null);
    }

    @Override
    public TokenizingModel analyze(final String filename, final byte[] blob, final Context c) {
        final String lang = language != null ? language : languageOf(filename);
        if (lang == null) {
            return null;
        }
        final SourceText text = SourceText.ofNormalized(blob);
        final org.w3c.dom.Element unit = parse(text.getContent(), lang, c);
        return unit == null ? null : new Model(filename, text, unit);
    }

    @Override
    public String languageName(final String filename) {
        return language != null ? language : languageOf(filename);
    }

    private org.w3c.dom.Element parse(final String source, final String lang, final Context c) {
        final String[] cmd = { command, "--language", lang, "--src-encoding", "UTF-8", "--position" };
        try (final ProcessRunner proc = new ProcessRunner(cmd, source.getBytes(StandardCharsets.UTF_8), c)) {
            final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            final DocumentBuilder builder = factory.newDocumentBuilder();
            final Document doc = builder.parse(new ByteArrayInputStream(proc.getResult()));
            return doc.getDocumentElement();
        } catch (final IOException | RuntimeException | javax.xml.parsers.ParserConfigurationException | org.xml.sax.SAXException e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    /**
     * A {@link TokenizingModel} backed by srcML, the srcML-free tree-sitter models' counterpart for
     * C, C++, C#, and Java. It walks the {@code srcml --position} XML (its {@code <unit>} root is supplied
     * by the enclosing {@link SrcmlAnalyzer}), mapping each {@code <class>}/{@code <function>}/{@code <constructor>}/
     * {@code <decl_stmt>} to an {@link Element} (a naming scope for a {@code <namespace>}) and producing a
     * token per leaf, typed by its enclosing srcML element and classified as comment or frame, for a
     * cregit or FinerGit token sequence. Because srcML is preprocessor-aware, it handles C/C++ that
     * defeats the tree-sitter parser. Its element and token names are not byte-identical to the
     * tree-sitter models'.
     */
    public static class Model implements TokenizingModel {
        private static final String SRC_NS = "http://www.srcML.org/srcML/src";

        private static final String POS_NS = "http://www.srcML.org/srcML/position";

        private final SourceText text;

        private final org.w3c.dom.Element unit;

        private final Element root;

        // the srcML DOM element each extracted Element came from, kept off the (neutral) Element itself
        private final Map<Element, org.w3c.dom.Element> nodes = new HashMap<>();

        private final int[] lineStart;

        Model(final String filename, final SourceText text, final org.w3c.dom.Element unit) {
            this.text = text;
            this.unit = unit;
            this.lineStart = lineStarts(text.getContent());
            final int index = filename.lastIndexOf('.');
            final String basename = index > 0 ? filename.substring(0, index) : filename;
            this.root = new Element(Element.Kind.FILE, basename);
            walk(unit, root);
        }

        @Override
        public Element getRoot() {
            return root;
        }

        // --- element extraction (historage) ---

        private void walk(final org.w3c.dom.Element node, final Element parent) {
            for (Node ch = node.getFirstChild(); ch != null; ch = ch.getNextSibling()) {
                if (!isElement(ch)) {
                    continue;
                }
                final org.w3c.dom.Element e = (org.w3c.dom.Element) ch;
                switch (e.getLocalName()) {
                    case "class", "struct", "union", "enum", "interface", "annotation_defn" -> {
                        walk(e, element(Element.Kind.CLASS, Signature.of(leafName(nameOf(e))), parent, e));
                    }
                    case "namespace" -> {
                        walk(e, element(Element.Kind.CLASS, Signature.of(leafName(nameOf(e))), parent, null));
                    }
                    case "function", "constructor", "destructor" -> {
                        element(Element.Kind.METHOD, methodSignature(e), parent, e);
                    }
                    case "decl_stmt" -> {
                        for (Node d = e.getFirstChild(); d != null; d = d.getNextSibling()) {
                            if (isElement(d, "decl")) {
                                element(Element.Kind.FIELD, Signature.of(leafName(nameOf((org.w3c.dom.Element) d))), parent, e);
                            }
                        }
                    }
                    case "block", "block_content", "public", "private", "protected", "unit", "extern" ->
                            walk(e, parent);
                    default -> {
                        // a statement, expression, or type: not an element and not descended
                    }
                }
            }
        }

        private Element element(final Element.Kind kind, final Signature signature, final Element parent, final org.w3c.dom.Element dom) {
            final Element e = new Element(kind, signature);
            if (dom != null) {
                nodes.put(e, dom);
                e.setCoreFragment(text.getFragmentOfLines(startLine(dom), endLine(dom)));
                final List<CommentAttachment.Node> comments = CommentAttachment.attached(new Sibling(dom));
                e.setComments(comments.stream().map(CommentAttachment.Node::fragment).toList());
                int extentStart = startLine(dom);
                int extentEnd = endLine(dom);
                for (final CommentAttachment.Node c : comments) {
                    extentStart = Math.min(extentStart, c.startRow());
                    extentEnd = Math.max(extentEnd, c.endRow());
                }
                e.setExtentFragment(text.getFragmentOfLines(extentStart, extentEnd));
                // the line range covers the extent, matching Element#rawText
                e.setStartLine(extentStart);
                e.setEndLine(extentEnd);
            }
            parent.addChild(e);
            return e;
        }

        /**
         * Adapts a srcML DOM element to the backend-neutral {@link CommentAttachment.Node} the attachment rule walks.
         * Rows are srcML's 1-based lines; the rule only compares them, so the base is immaterial.
         */
        private final class Sibling implements CommentAttachment.Node {
            private final org.w3c.dom.Element node;

            Sibling(final org.w3c.dom.Element node) {
                this.node = node;
            }

            @Override
            public CommentAttachment.Node previous() {
                final org.w3c.dom.Element p = prevElement(node);
                return p == null ? null : new Sibling(p);
            }

            @Override
            public CommentAttachment.Node next() {
                final org.w3c.dom.Element n = nextElement(node);
                return n == null ? null : new Sibling(n);
            }

            @Override
            public int startRow() {
                return startLine(node);
            }

            @Override
            public int endRow() {
                return endLine(node);
            }

            @Override
            public boolean isComment() {
                return Model.this.isComment(node);
            }

            @Override
            public boolean isDocComment() {
                return node.getTextContent().trim().startsWith("/**");
            }

            @Override
            public boolean isNamed() {
                return !Model.this.isComment(node);
            }

            @Override
            public Fragment fragment() {
                // srcML's end position is inclusive (the last character), so add one for an exclusive bound
                return text.getFragment(offset(startLine(node), startCol(node)), offset(endLine(node), endCol(node) + 1));
            }
        }

        private boolean isComment(final org.w3c.dom.Element e) {
            return "comment".equals(e.getLocalName());
        }

        private org.w3c.dom.Element prevElement(final Node node) {
            for (Node p = node.getPreviousSibling(); p != null; p = p.getPreviousSibling()) {
                if (isElement(p)) {
                    return (org.w3c.dom.Element) p;
                }
            }
            return null;
        }

        private org.w3c.dom.Element nextElement(final Node node) {
            for (Node n = node.getNextSibling(); n != null; n = n.getNextSibling()) {
                if (isElement(n)) {
                    return (org.w3c.dom.Element) n;
                }
            }
            return null;
        }

        // --- tokens (historage token sequence, cregit) ---

        @Override
        public List<Token> tokens(final Element e) {
            final List<Token> out = new ArrayList<>();
            if (e == root) {
                collectTokens(unit, null, out);
            } else {
                final org.w3c.dom.Element node = nodes.get(e);
                collectTokens(node, frameOf(node), out);
            }
            return out;
        }

        private void collectTokens(final Node node, final Frame frame, final List<Token> out) {
            for (Node ch = node.getFirstChild(); ch != null; ch = ch.getNextSibling()) {
                if (ch.getNodeType() == Node.TEXT_NODE) {
                    token(ch, frame, out);
                } else if (isElement(ch)) {
                    collectTokens(ch, frame, out);
                }
            }
        }

        private void token(final Node node, final Frame frame, final List<Token> out) {
            final String trimmed = node.getTextContent().trim().replace('\n', ' ').replace("\r", "");
            if (trimmed.isEmpty()) {
                return;
            }
            final org.w3c.dom.Element parent = (org.w3c.dom.Element) node.getParentNode();
            final boolean comment = "comment".equals(parent.getLocalName());
            out.add(new Token(trimmed, parent.getLocalName(), startLine(parent), startCol(parent), 0,
                    comment, frame != null && isFrame(trimmed, parent, frame)));
        }

        /**
         * The frame context of a declaration element: its {@code parameter_list} and {@code block} children
         * (either possibly null) and whether it is a function, against which a leaf is tested by
         * {@link #isFrame}.
         */
        private record Frame(org.w3c.dom.Element parameters, org.w3c.dom.Element block, org.w3c.dom.Element root,
                             boolean function) {
        }

        private Frame frameOf(final org.w3c.dom.Element declaration) {
            return new Frame(child(declaration, "parameter_list"), child(declaration, "block"), declaration,
                    isFunction(declaration.getLocalName()));
        }

        private static boolean isFunction(final String localName) {
            return switch (localName) {
                case "function", "constructor", "destructor" -> true;
                default -> false;
            };
        }

        /**
         * Whether the leaf is one of the declaration's frame delimiters (Heuristic 2): the parentheses of
         * its parameter list, the braces of its body, or a bodyless declaration's terminating semicolon.
         * srcML emits an empty parameter list or body as a single {@code ()}/{@code {}} text node, so a leaf
         * counts when it is wholly the enclosing delimiter's punctuation (leaving a {@code ,} separator).
         */
        private boolean isFrame(final String text, final org.w3c.dom.Element parent, final Frame frame) {
            if (frame.parameters() != null && parent == frame.parameters()) {
                return text.chars().allMatch(ch -> ch == '(' || ch == ')');
            }
            if (frame.block() != null && parent == frame.block()) {
                return text.chars().allMatch(ch -> ch == '{' || ch == '}');
            }
            return frame.function() && ";".equals(text) && parent == frame.root();
        }

        @Override
        public void walkTokens(final TokenVisitor sink) {
            walkTokens(unit, sink);
        }

        private void walkTokens(final Node node, final TokenVisitor sink) {
            for (Node ch = node.getFirstChild(); ch != null; ch = ch.getNextSibling()) {
                if (ch.getNodeType() == Node.TEXT_NODE) {
                    token(ch, sink);
                } else if (isElement(ch)) {
                    final Element.Kind kind = kindOf(ch.getLocalName());
                    if (kind == null) {
                        walkTokens(ch, sink);
                    } else {
                        sink.begin(kind);
                        walkTokens(ch, sink);
                        sink.end(kind);
                    }
                }
            }
        }

        private void token(final Node node, final TokenVisitor sink) {
            final String trimmed = node.getTextContent().trim().replace('\n', ' ').replace("\r", "");
            if (!trimmed.isEmpty()) {
                final org.w3c.dom.Element parent = (org.w3c.dom.Element) node.getParentNode();
                sink.token(new Token(trimmed, parent.getLocalName(), startLine(parent), startCol(parent), 0));
            }
        }

        private Element.Kind kindOf(final String localName) {
            return switch (localName) {
                case "class", "struct", "union", "enum", "interface", "annotation_defn" -> Element.Kind.CLASS;
                case "function", "constructor", "destructor" -> Element.Kind.METHOD;
                case "decl_stmt" -> Element.Kind.FIELD;
                default -> null;
            };
        }

        // --- naming helpers ---

        private Signature methodSignature(final org.w3c.dom.Element e) {
            final org.w3c.dom.Element params = child(e, "parameter_list");
            return new Signature(leafName(nameOf(e)), null, params == null ? List.of() : parameterTypes(params));
        }

        private List<String> parameterTypes(final org.w3c.dom.Element parameterList) {
            final List<String> types = new ArrayList<>();
            for (Node p = parameterList.getFirstChild(); p != null; p = p.getNextSibling()) {
                if (isElement(p, "parameter")) {
                    final org.w3c.dom.Element decl = child((org.w3c.dom.Element) p, "decl");
                    final org.w3c.dom.Element type = decl == null ? null : child(decl, "type");
                    if (type != null) {
                        types.add(type.getTextContent().trim());
                    }
                }
            }
            return types;
        }

        private String nameOf(final org.w3c.dom.Element e) {
            final org.w3c.dom.Element name = child(e, "name");
            return name == null ? "" : name.getTextContent().trim();
        }

        private String leafName(final String name) {
            final int i = name.lastIndexOf("::");
            return i < 0 ? name : name.substring(i + 2);
        }

        private org.w3c.dom.Element child(final org.w3c.dom.Element e, final String localName) {
            for (Node ch = e.getFirstChild(); ch != null; ch = ch.getNextSibling()) {
                if (isElement(ch, localName)) {
                    return (org.w3c.dom.Element) ch;
                }
            }
            return null;
        }

        private boolean isElement(final Node node) {
            return node.getNodeType() == Node.ELEMENT_NODE && SRC_NS.equals(node.getNamespaceURI());
        }

        private boolean isElement(final Node node, final String localName) {
            return isElement(node) && localName.equals(node.getLocalName());
        }

        // --- position helpers ---

        private int startLine(final org.w3c.dom.Element e) {
            return part(e.getAttributeNS(POS_NS, "start"), 0);
        }

        private int startCol(final org.w3c.dom.Element e) {
            return part(e.getAttributeNS(POS_NS, "start"), 1);
        }

        private int endLine(final org.w3c.dom.Element e) {
            return part(e.getAttributeNS(POS_NS, "end"), 0);
        }

        private int endCol(final org.w3c.dom.Element e) {
            return part(e.getAttributeNS(POS_NS, "end"), 1);
        }

        private int part(final String position, final int index) {
            final int i = position.indexOf(':');
            if (i < 0) {
                return 0;
            }
            return index == 0 ? Integer.parseInt(position.substring(0, i)) : Integer.parseInt(position.substring(i + 1));
        }

        /**
         * The character offset of a srcML position. srcML reports columns as UTF-8 byte offsets within the
         * line, so the byte position is mapped back to a character index.
         */
        private int offset(final int line, final int col) {
            if (line <= 0 || line > lineStart.length) {
                return 0;
            }
            return text.toCharIndex(lineStart[line - 1] + (col - 1));
        }

        /**
         * The UTF-8 byte offset of each line start, against which srcML's byte columns are resolved.
         */
        private static int[] lineStarts(final String content) {
            final byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            final List<Integer> starts = new ArrayList<>();
            starts.add(0);
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] == '\n') {
                    starts.add(i + 1);
                }
            }
            return starts.stream().mapToInt(Integer::intValue).toArray();
        }
    }
}
