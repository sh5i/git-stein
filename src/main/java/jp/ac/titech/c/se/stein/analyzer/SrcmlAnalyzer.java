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
import jp.ac.titech.c.se.stein.util.ProcessRunner;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@link TokenizingAnalyzer} backed by srcML, the srcML-free tree-sitter analyzers' counterpart for
 * C, C++, C#, and Java. It walks the {@code srcml --position} XML (its {@code <unit>} root is supplied
 * by {@link Srcml}), mapping each {@code <class>}/{@code <function>}/{@code <constructor>}/
 * {@code <decl_stmt>} to an {@link Element} (a naming scope for a {@code <namespace>}) and, for cregit,
 * producing a token per leaf typed by its enclosing srcML element. Because srcML is preprocessor-aware,
 * it handles C/C++ that defeats the tree-sitter parser. Its element and token names are not
 * byte-identical to the tree-sitter analyzers.
 */
@Slf4j
public class SrcmlAnalyzer implements TokenizingAnalyzer {
    private static final String SRC_NS = "http://www.srcML.org/srcML/src";

    private static final String POS_NS = "http://www.srcML.org/srcML/position";

    public static final String[] JAVA_EXT = {".java", ".aj", ".mjava", ".fjava", ".cjava"};
    public static final String[] C_EXT = {".c", ".h", ".i"};
    public static final String[] CXX_EXT = {".cpp", ".CPP", ".cp", ".hpp", ".cxx", ".hxx", ".cc", ".hh", ".c++", ".h++", ".C", ".H", ".tcc", ".ii"};
    public static final String[] CSHARP_EXT = {".cs"};

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

    public static boolean accepts(final String filename) {
        return languageOf(filename) != null;
    }

    private static boolean endsWithAny(final String filename, final String[] extensions) {
        for (final String ext : extensions) {
            if (filename.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Parses the blob with {@code srcml --position} and returns a ready analyzer, or null when srcML
     * does not handle the file name or the parse fails. The language may be forced (for a file name
     * srcML would not map).
     */
    public static SrcmlAnalyzer of(final String filename, final byte[] blob, final String srcml,
                                   final String language, final Context c) {
        final String lang = language != null ? language : languageOf(filename);
        if (lang == null) {
            return null;
        }
        final SourceText text = SourceText.ofNormalized(blob);
        final org.w3c.dom.Element unit = parse(text.getContent(), lang, srcml, c);
        return unit == null ? null : new SrcmlAnalyzer(filename, text, unit);
    }

    private static org.w3c.dom.Element parse(final String source, final String lang, final String srcml, final Context c) {
        final String[] cmd = { srcml, "--language", lang, "--position" };
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

    private final SourceText text;

    private final org.w3c.dom.Element unit;

    private final Element root;

    // the srcML DOM element each extracted Element came from, kept off the (neutral) Element itself
    private final Map<Element, org.w3c.dom.Element> nodes = new HashMap<>();

    private final int[] lineStart;

    private boolean extracted;

    SrcmlAnalyzer(final String filename, final SourceText text, final org.w3c.dom.Element unit) {
        this.text = text;
        this.unit = unit;
        this.lineStart = lineStarts(text.getContent());
        final int index = filename.lastIndexOf('.');
        final String basename = index > 0 ? filename.substring(0, index) : filename;
        this.root = new Element(Element.Kind.FILE, basename, 0, text.getContent().length());
    }

    @Override
    public Element extract() {
        if (!extracted) {
            walk(unit, root);
            extracted = true;
        }
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
        final Element e = dom == null ? new Element(kind, signature)
                : new Element(kind, signature, offset(startLine(dom), startCol(dom)), offset(endLine(dom), endCol(dom)));
        if (dom != null) {
            nodes.put(e, dom);
            e.setStartLine(startLine(dom));
            e.setEndLine(endLine(dom));
        }
        parent.addChild(e);
        return e;
    }

    // --- rendering (historage) ---

    @Override
    public String render(final Element e, final RenderOptions options) {
        final org.w3c.dom.Element dom = nodes.get(e);
        return text.getFragmentOfLines(startLine(dom), endLine(dom)).getWiderContent();
    }

    // --- tokens (cregit) ---

    @Override
    public List<Token> tokens(final Element e) {
        final List<Token> out = new ArrayList<>();
        collectTokens(e == root ? unit : nodes.get(e), out);
        return out;
    }

    private void collectTokens(final Node node, final List<Token> out) {
        for (Node ch = node.getFirstChild(); ch != null; ch = ch.getNextSibling()) {
            if (ch.getNodeType() == Node.TEXT_NODE) {
                token(ch, out);
            } else if (isElement(ch)) {
                token(ch, out); // a leaf element's own text is reached by recursion; its type is the element
                collectTokens(ch, out);
            }
        }
    }

    private void token(final Node node, final List<Token> out) {
        if (node.getNodeType() != Node.TEXT_NODE) {
            return;
        }
        final String trimmed = node.getTextContent().trim().replace('\n', ' ').replace("\r", "");
        if (trimmed.isEmpty()) {
            return;
        }
        final org.w3c.dom.Element parent = (org.w3c.dom.Element) node.getParentNode();
        out.add(new Token(trimmed, parent.getLocalName(), startLine(parent), startCol(parent), 0));
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

    private int offset(final int line, final int col) {
        if (line <= 0 || line > lineStart.length) {
            return 0;
        }
        return lineStart[line - 1] + (col - 1);
    }

    private static int[] lineStarts(final String content) {
        final List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                starts.add(i + 1);
            }
        }
        return starts.stream().mapToInt(Integer::intValue).toArray();
    }
}
