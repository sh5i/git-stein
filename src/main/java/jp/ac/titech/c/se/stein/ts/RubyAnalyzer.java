package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a Ruby file: classes, modules (naming scopes), methods (instance and singleton
 * {@code def self.x}), and top-level constant assignments (fields).
 */
public class RubyAnalyzer extends LanguageAnalyzer {
    public RubyAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
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
                case "class" -> visitClass(child, parent);
                case "module" -> visitModule(child, parent);
                case "method", "singleton_method" -> visitMethod(child, parent);
                case "assignment" -> visitAssignment(child, parent);
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
        final TSNode body = node.getChildByFieldName("body");
        if (!body.isNull()) {
            walk(body, klass);
        }
    }

    protected void visitModule(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        final Element scope = name.isNull() ? parent : element(ElementKind.CLASS, flatten(textOf(name)), parent, null);
        final TSNode body = node.getChildByFieldName("body");
        if (!body.isNull()) {
            walk(body, scope);
        }
    }

    protected void visitMethod(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        element(ElementKind.METHOD, flatten(textOf(name)) + "(" + signature(node.getChildByFieldName("parameters")) + ")", parent, node);
    }

    protected void visitAssignment(final TSNode node, final Element parent) {
        final TSNode left = node.getChildByFieldName("left");
        if (!left.isNull() && left.getType().equals("constant")) {
            element(ElementKind.FIELD, flatten(textOf(left)), parent, node);
        }
    }

    protected String signature(final TSNode parameters) {
        if (parameters == null || parameters.isNull()) {
            return "";
        }
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < parameters.getNamedChildCount(); i++) {
            final TSNode p = parameters.getNamedChild(i);
            final TSNode name = p.getChildByFieldName("name");
            names.add(escape(textOf(name.isNull() ? p : name).replaceAll("\\s+", "")));
        }
        return String.join(",", names);
    }
}
