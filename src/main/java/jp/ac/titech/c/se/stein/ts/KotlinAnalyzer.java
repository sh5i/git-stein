package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a Kotlin file: classes, interfaces, objects, and enums (classes), functions, and
 * properties. The Kotlin grammar uses few field names, so names are found by child type.
 */
public class KotlinAnalyzer extends LanguageAnalyzer {
    public KotlinAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
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
                case "class_declaration", "object_declaration" -> visitClass(child, parent);
                case "function_declaration" -> visitFunction(child, parent);
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
        final TSNode body = firstChildOfType(node, "class_body");
        if (body != null) {
            walk(body, klass);
        }
    }

    protected void visitFunction(final TSNode node, final Element parent) {
        final TSNode name = firstChildOfType(node, "simple_identifier");
        if (name != null) {
            element(ElementKind.METHOD, flatten(textOf(name)) + "(" + signature(node) + ")", parent, node);
        }
    }

    protected void visitProperty(final TSNode node, final Element parent) {
        final TSNode decl = firstChildOfType(node, "variable_declaration");
        final TSNode name = decl != null ? firstChildOfType(decl, "simple_identifier") : null;
        if (name != null) {
            element(ElementKind.FIELD, flatten(textOf(name)), parent, node);
        }
    }

    protected String signature(final TSNode node) {
        final TSNode params = firstChildOfType(node, "function_value_parameters");
        if (params == null) {
            return "";
        }
        final List<String> types = new ArrayList<>();
        for (int i = 0; i < params.getNamedChildCount(); i++) {
            final TSNode p = params.getNamedChild(i);
            if (p.getType().equals("parameter")) {
                final TSNode type = firstChildOfType(p, "user_type");
                types.add(escape(textOf(type != null ? type : p).replaceAll("\\s+", "")));
            }
        }
        return String.join(",", types);
    }
}
