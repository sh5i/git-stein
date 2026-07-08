package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a Swift file: classes, structs, enums (classes), protocols, functions, initializers, and
 * properties. The Swift grammar overloads field names, so names are found by child type.
 */
public class SwiftAnalyzer extends LanguageAnalyzer {
    public SwiftAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
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
                case "class_declaration", "protocol_declaration" -> visitClass(child, parent);
                case "function_declaration", "protocol_function_declaration" -> visitFunction(child, parent);
                case "init_declaration" -> visitInit(child, parent);
                case "property_declaration" -> visitProperty(child, parent);
                default -> {
                    if (!child.isError()) {
                        walk(child, parent);
                    }
                }
            }
        }
    }

    protected void visitClass(final TSNode node, final Element parent) {
        final TSNode name = firstChildOfType(node, "type_identifier");
        if (name == null) {
            return;
        }
        final Element klass = element(ElementKind.CLASS, flatten(textOf(name)), parent, node);
        for (final String bodyType : new String[] {"class_body", "enum_class_body", "protocol_body"}) {
            final TSNode body = firstChildOfType(node, bodyType);
            if (body != null) {
                walk(body, klass);
            }
        }
    }

    protected void visitFunction(final TSNode node, final Element parent) {
        final TSNode name = firstChildOfType(node, "simple_identifier");
        if (name != null) {
            element(ElementKind.METHOD, flatten(textOf(name)) + "(" + signature(node) + ")", parent, node);
        }
    }

    protected void visitInit(final TSNode node, final Element parent) {
        element(ElementKind.METHOD, "init(" + signature(node) + ")", parent, node);
    }

    protected void visitProperty(final TSNode node, final Element parent) {
        final TSNode pattern = node.getChildByFieldName("name");
        final TSNode name = pattern.isNull() ? null : pattern.getChildByFieldName("bound_identifier");
        if (name != null && !name.isNull()) {
            element(ElementKind.FIELD, flatten(textOf(name)), parent, node);
        }
    }

    protected String signature(final TSNode node) {
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode p = node.getNamedChild(i);
            if (p.getType().equals("parameter")) {
                final TSNode name = firstChildOfType(p, "simple_identifier");
                names.add(escape(textOf(name != null ? name : p).replaceAll("\\s+", "")));
            }
        }
        return String.join(",", names);
    }
}
