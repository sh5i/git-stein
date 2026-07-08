package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;

import lombok.Getter;

/**
 * A source element a {@link LanguageAnalyzer} extracts: a named class, method, or field, or the
 * virtual {@link ElementKind#FILE} root. Elements nest into a tree mirroring the source's scope
 * structure, which a consumer walks to build its own model (e.g. Historage modules).
 *
 * <p>An element carries the tree-sitter node whose text is its content; the analyzer renders it on
 * demand via {@link LanguageAnalyzer#render}. A scope such as a namespace, which structures names but
 * is never rendered, has no content node ({@link #hasContent} is false). The node is kept
 * package-private so tree-sitter types stay inside this package.</p>
 */
public class Element {
    @Getter
    private final ElementKind kind;

    @Getter
    private final String name;

    final TSNode node;

    @Getter
    private final List<Element> children = new ArrayList<>();

    Element(final ElementKind kind, final String name, final TSNode node) {
        this.kind = kind;
        this.name = name;
        this.node = node;
    }

    void addChild(final Element child) {
        children.add(child);
    }

    /**
     * Whether this element has renderable content, as opposed to being a naming scope only.
     */
    public boolean hasContent() {
        return node != null;
    }
}
