package jp.ac.titech.c.se.stein.analyzer;

import java.util.ArrayList;
import java.util.List;

import jp.ac.titech.c.se.stein.core.SourceText.Fragment;
import lombok.Getter;
import lombok.Setter;

/**
 * A source element a {@link SourceAnalyzer} extracts: a named class, method, or field, or the virtual
 * {@link Kind#FILE} root. Elements nest into a tree mirroring the source's scope structure, which a
 * consumer walks to build its own model (e.g. Historage modules).
 *
 * <p>An element is a backend-neutral value: it carries the character range that locates its content and
 * a {@link Fragment} of the decoded text from which its raw source is rendered, not a tree-sitter node.
 * A scope such as a namespace, which structures names but is never rendered, has no content range
 * ({@link #hasContent} is false). A few grammars split a declaration into two adjacent nodes; such an
 * element carries a second, {@code span} range so the analyzer can render both parts.</p>
 */
public class Element {
    /**
     * The category of a source element: the virtual file root, a class-like scope, a method, or a
     * field. It is deliberately language-neutral; a consumer maps it to its own model.
     */
    public enum Kind {
        FILE, CLASS, METHOD, FIELD
    }

    static final int NONE = -1;

    @Getter
    private final Kind kind;

    @Getter
    private final Signature signature;

    /**
     * The character range (indices into the decoded content) of the element's content, or
     * {@link #NONE} for a naming scope with no content.
     */
    final int start;
    final int end;

    /**
     * When set (not {@link #NONE}), the character range of the second node this element spans, adjacent
     * to its content; used for grammars that split a declaration into separate sibling nodes (e.g.
     * Dart's method signature and body).
     */
    final int spanStart;
    final int spanEnd;

    /**
     * The element's source span, from which its raw text is rendered, or null for a scope with no
     * content.
     */
    @Getter
    @Setter
    private Fragment fragment;

    /**
     * The 1-based source line range of the element's content, or {@link #NONE} when the analyzer does
     * not track lines. The analyzer sets it to the same region its {@link SourceAnalyzer#render} covers,
     * so a Historage mapping file can record where each module came from.
     */
    @Getter
    @Setter
    private int startLine = NONE;

    @Getter
    @Setter
    private int endLine = NONE;

    /**
     * An analyzer-specific kind label beyond the neutral {@link Kind}, or null when the analyzer has
     * none. A tag-based analyzer such as ctags sets it to its own richer kind (e.g. {@code enumConstant},
     * {@code package}); a naming strategy for that analyzer may use it (e.g. as the file extension).
     */
    @Getter
    @Setter
    private String rawKind;

    @Getter
    private Element parent;

    @Getter
    private final List<Element> children = new ArrayList<>();

    Element(final Kind kind, final String name) {
        this(kind, Signature.of(name));
    }

    Element(final Kind kind, final String name, final int start, final int end) {
        this(kind, Signature.of(name), start, end);
    }

    Element(final Kind kind, final Signature signature) {
        this(kind, signature, NONE, NONE, NONE, NONE);
    }

    Element(final Kind kind, final Signature signature, final int start, final int end) {
        this(kind, signature, start, end, NONE, NONE);
    }

    Element(final Kind kind, final Signature signature, final int start, final int end,
            final int spanStart, final int spanEnd) {
        this.kind = kind;
        this.signature = signature;
        this.start = start;
        this.end = end;
        this.spanStart = spanStart;
        this.spanEnd = spanEnd;
    }

    void addChild(final Element child) {
        child.parent = this;
        children.add(child);
    }

    /**
     * The element's simple name, a shorthand for {@code getSignature().name()}.
     */
    public String getName() {
        return signature.name();
    }

    /**
     * Whether this element has renderable content, as opposed to being a naming scope only.
     */
    public boolean hasContent() {
        return start != NONE;
    }

    boolean hasSpan() {
        return spanStart != NONE;
    }

    /**
     * The character offset where this element's source begins, or -1 for a scope with no source of its
     * own.
     */
    public int getStart() {
        return start;
    }

    /**
     * The character offset where this element's source ends (past its spanned body, if any), or -1 for
     * a scope.
     */
    public int getEnd() {
        if (start == NONE) {
            return NONE;
        }
        return spanStart != NONE ? spanEnd : end;
    }
}
