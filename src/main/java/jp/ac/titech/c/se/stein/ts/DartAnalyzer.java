package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a Dart file: classes (with methods, constructors, and fields), free functions, and
 * top-level variables. The Dart grammar splits a function into a signature node and a separate body
 * node, so a method spans the two.
 */
public class DartAnalyzer extends LanguageAnalyzer {
    public DartAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
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
                case "class_definition", "mixin_declaration", "extension_declaration" -> visitClass(child, parent);
                case "enum_declaration" -> visitEnum(child, parent);
                case "method_signature", "function_signature" -> visitFunction(child, parent);
                case "initialized_identifier_list" -> visitFields(child, parent, child);
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
        if (body.isNull()) {
            return;
        }
        for (int i = 0; i < body.getNamedChildCount(); i++) {
            final TSNode member = body.getNamedChild(i);
            switch (member.getType()) {
                case "method_signature" -> visitFunction(member, klass);
                case "declaration" -> visitDeclaration(member, klass);
                default -> { }
            }
        }
    }

    protected void visitEnum(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        if (name.isNull()) {
            return;
        }
        final Element klass = element(ElementKind.CLASS, flatten(textOf(name)), parent, node);
        final TSNode body = node.getChildByFieldName("body");
        if (body.isNull()) {
            return;
        }
        for (int i = 0; i < body.getNamedChildCount(); i++) {
            final TSNode constant = body.getNamedChild(i);
            if (constant.getType().equals("enum_constant")) {
                final TSNode constantName = constant.getChildByFieldName("name");
                if (!constantName.isNull()) {
                    element(ElementKind.FIELD, flatten(textOf(constantName)), klass, constant);
                }
            }
        }
    }

    /**
     * Visits a function or method (including a getter, setter, or factory constructor); when the
     * following sibling is its body, the element spans both.
     */
    protected void visitFunction(final TSNode node, final Element parent) {
        final TSNode sig = node.getType().equals("method_signature") ? firstSignature(node) : node;
        if (sig == null) {
            return;
        }
        final TSNode name = signatureName(sig);
        if (name == null || name.isNull()) {
            return;
        }
        final String label = flatten(textOf(name)) + "(" + signature(sig) + ")";
        final TSNode next = node.getNextNamedSibling();
        if (!next.isNull() && next.getType().equals("function_body")) {
            element(ElementKind.METHOD, label, parent, node, next);
        } else {
            element(ElementKind.METHOD, label, parent, node);
        }
    }

    /**
     * The signature node inside a method signature: a function, getter, setter, or factory constructor.
     */
    protected TSNode firstSignature(final TSNode methodSignature) {
        for (int i = 0; i < methodSignature.getNamedChildCount(); i++) {
            final TSNode child = methodSignature.getNamedChild(i);
            if (child.getType().endsWith("_signature")) {
                return child;
            }
        }
        return null;
    }

    /**
     * The declared name of a signature. A factory constructor names the factory (its last identifier);
     * every other signature carries a {@code name} field.
     */
    protected TSNode signatureName(final TSNode sig) {
        final TSNode name = sig.getChildByFieldName("name");
        return name.isNull() ? lastChildOfType(sig, "identifier") : name;
    }

    protected TSNode lastChildOfType(final TSNode node, final String type) {
        TSNode found = null;
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            if (child.getType().equals(type)) {
                found = child;
            }
        }
        return found;
    }

    protected void visitDeclaration(final TSNode node, final Element parent) {
        final TSNode ids = firstChildOfType(node, "initialized_identifier_list");
        if (ids != null) {
            visitFields(ids, parent, node);
            return;
        }
        final TSNode constructor = firstChildOfType(node, "constructor_signature");
        if (constructor != null) {
            final TSNode name = lastChildOfType(constructor, "identifier");
            if (name != null) {
                element(ElementKind.METHOD, flatten(textOf(name)) + "(" + signature(constructor) + ")", parent, node);
            }
        }
    }

    protected void visitFields(final TSNode list, final Element parent, final TSNode content) {
        for (int i = 0; i < list.getNamedChildCount(); i++) {
            final TSNode child = list.getNamedChild(i);
            if (child.getType().equals("initialized_identifier")) {
                final TSNode name = firstChildOfType(child, "identifier");
                if (name != null) {
                    element(ElementKind.FIELD, flatten(textOf(name)), parent, content);
                }
            }
        }
    }

    protected String signature(final TSNode functionSignature) {
        final TSNode params = firstChildOfType(functionSignature, "formal_parameter_list");
        if (params == null) {
            return "";
        }
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < params.getNamedChildCount(); i++) {
            final TSNode name = params.getNamedChild(i).getChildByFieldName("name");
            if (!name.isNull()) {
                names.add(escape(textOf(name).replaceAll("\\s+", "")));
            }
        }
        return String.join(",", names);
    }
}
