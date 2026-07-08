package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a PHP file: classes, interfaces, and traits (classes, with their methods and properties),
 * free functions (methods), and namespaces (naming scopes). A block namespace scopes its body; a
 * statement namespace ({@code namespace N;}) scopes the siblings that follow it.
 */
public class PhpAnalyzer extends LanguageAnalyzer {
    public PhpAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected void run() {
        walk(treeRoot, root);
    }

    protected void walk(final TSNode node, Element parent) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            switch (child.getType()) {
                case "namespace_definition" -> parent = visitNamespace(child, parent);
                case "class_declaration", "interface_declaration", "trait_declaration",
                     "enum_declaration" -> visitType(child, parent);
                case "method_declaration", "function_definition" -> visitMethod(child, parent);
                case "property_declaration" -> visitProperty(child, parent);
                case "const_declaration" -> visitConst(child, parent);
                case "enum_case" -> {
                    final TSNode name = child.getChildByFieldName("name");
                    if (!name.isNull()) {
                        element(ElementKind.FIELD, flatten(textOf(name)), parent, child);
                    }
                }
                default -> {
                    if (!child.isError()) {
                        walk(child, parent);
                    }
                }
            }
        }
    }

    /**
     * Descends a block namespace's body and returns the unchanged parent, or, for a statement
     * namespace with no body, returns the scope so the following siblings nest under it.
     */
    protected Element visitNamespace(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        final Element scope = name.isNull() ? parent
                : element(ElementKind.CLASS, flatten(textOf(name).replace("\\", ".")), parent, null);
        final TSNode body = node.getChildByFieldName("body");
        if (!body.isNull()) {
            walk(body, scope);
            return parent;
        }
        return scope;
    }

    protected void visitType(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        if (name.isNull()) {
            return;
        }
        final Element klass = element(ElementKind.CLASS, flatten(textOf(name)), parent, node);
        TSNode body = node.getChildByFieldName("body");
        if (body.isNull()) {
            final TSNode enumBody = firstChildOfType(node, "enum_declaration_list");
            if (enumBody != null) {
                body = enumBody;
            }
        }
        if (!body.isNull()) {
            walk(body, klass);
        }
    }

    protected void visitMethod(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        if (!name.isNull()) {
            element(ElementKind.METHOD, flatten(textOf(name)) + "(" + signature(node.getChildByFieldName("parameters")) + ")", parent, node);
        }
    }

    protected void visitProperty(final TSNode node, final Element parent) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            if (child.getType().equals("property_element")) {
                final TSNode name = firstChildOfType(child, "variable_name");
                if (name != null) {
                    element(ElementKind.FIELD, flatten(textOf(name)), parent, node);
                }
            }
        }
    }

    protected void visitConst(final TSNode node, final Element parent) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            if (child.getType().equals("const_element")) {
                final TSNode name = firstChildOfType(child, "name");
                if (name != null) {
                    element(ElementKind.FIELD, flatten(textOf(name)), parent, node);
                }
            }
        }
    }

    protected String signature(final TSNode parameters) {
        if (parameters.isNull()) {
            return "";
        }
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < parameters.getNamedChildCount(); i++) {
            final TSNode name = parameters.getNamedChild(i).getChildByFieldName("name");
            if (!name.isNull()) {
                names.add(escape(textOf(name).replaceAll("\\s+", "")));
            }
        }
        return String.join(",", names);
    }
}
