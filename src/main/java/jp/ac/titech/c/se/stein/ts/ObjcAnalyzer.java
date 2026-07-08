package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes an Objective-C file: {@code @interface} and {@code @implementation} blocks (classes) with
 * their methods (named by their selector, e.g. {@code doThing:with:}), properties, and instance
 * variables. C functions are left to whole-file tokenization.
 */
public class ObjcAnalyzer extends LanguageAnalyzer {
    public ObjcAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected void run() {
        walk(treeRoot, root);
    }

    protected void walk(final TSNode node, final Element parent) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            switch (child.getType()) {
                case "class_interface", "class_implementation",
                     "category_interface", "category_implementation" -> visitClass(child, parent);
                default -> {
                    if (!child.isError()) {
                        walk(child, parent);
                    }
                }
            }
        }
    }

    protected void visitClass(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        if (name.isNull()) {
            return;
        }
        final Element klass = element(ElementKind.CLASS, flatten(textOf(name)), parent, node);
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode member = node.getNamedChild(i);
            switch (member.getType()) {
                case "method_declaration", "method_definition" ->
                        element(ElementKind.METHOD, flatten(selectorName(member)), klass, member);
                case "property_declaration", "field_declaration" -> {
                    final TSNode declarator = member.getChildByFieldName("declarator");
                    if (!declarator.isNull()) {
                        element(ElementKind.FIELD, flatten(textOf(declarator)), klass, member);
                    }
                }
                default -> { }
            }
        }
    }

    /**
     * The selector of a method: a keyword selector joins its keyword parts with colons
     * ({@code doThing:with:}), a unary selector is a bare name ({@code simple}).
     */
    protected String selectorName(final TSNode method) {
        final TSNode selector = method.getChildByFieldName("selector");
        if (selector.isNull()) {
            return "";
        }
        if (!selector.getType().equals("keyword_selector")) {
            return textOf(selector);
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selector.getNamedChildCount(); i++) {
            final TSNode part = selector.getNamedChild(i);
            if (part.getType().equals("keyword_declarator")) {
                sb.append(textOf(part.getChildByFieldName("keyword"))).append(":");
            }
        }
        return sb.toString();
    }
}
