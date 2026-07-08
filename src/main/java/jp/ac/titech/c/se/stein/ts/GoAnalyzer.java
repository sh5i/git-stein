package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a Go file: types (structs and interfaces become classes, with their fields and interface
 * methods split out; other type specs are fields), free functions, methods (named
 * {@code Receiver.name}), and package-level constants and variables.
 */
public class GoAnalyzer extends LanguageAnalyzer {
    public GoAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
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
                case "type_declaration" -> visitTypeDeclaration(child, parent);
                case "function_declaration" -> visitFunction(child, parent);
                case "method_declaration" -> visitMethod(child, parent);
                case "const_declaration", "var_declaration" -> visitVariables(child, parent);
                default -> {
                    if (!child.isError()) {
                        walk(child, parent);
                    }
                }
            }
        }
    }

    protected void visitTypeDeclaration(final TSNode node, final Element parent) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode spec = node.getNamedChild(i);
            if (!spec.getType().equals("type_spec")) {
                continue;
            }
            final TSNode name = spec.getChildByFieldName("name");
            final TSNode type = spec.getChildByFieldName("type");
            if (name.isNull()) {
                continue;
            }
            if (type.getType().equals("struct_type") || type.getType().equals("interface_type")) {
                final Element klass = element(ElementKind.CLASS, flatten(textOf(name)), parent, node);
                visitTypeBody(type, klass);
            } else {
                element(ElementKind.FIELD, flatten(textOf(name)), parent, node);
            }
        }
    }

    protected void visitTypeBody(final TSNode type, final Element klass) {
        final TSNode body = type.getType().equals("struct_type")
                ? firstChildOfType(type, "field_declaration_list") : firstChildOfType(type, "interface_type_body");
        final TSNode list = body != null ? body : type;
        for (int i = 0; i < list.getNamedChildCount(); i++) {
            final TSNode member = list.getNamedChild(i);
            if (member.getType().equals("field_declaration")) {
                for (int j = 0; j < member.getNamedChildCount(); j++) {
                    final TSNode f = member.getNamedChild(j);
                    if (f.getType().equals("field_identifier")) {
                        element(ElementKind.FIELD, flatten(textOf(f)), klass, member);
                    }
                }
            } else if (member.getType().equals("method_elem") || member.getType().equals("method_spec")) {
                final TSNode mn = member.getChildByFieldName("name");
                element(ElementKind.METHOD, flatten(textOf(mn)) + "(" + signature(member.getChildByFieldName("parameters")) + ")", klass, member);
            }
        }
    }

    protected void visitFunction(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        element(ElementKind.METHOD, flatten(textOf(name)) + "(" + signature(node.getChildByFieldName("parameters")) + ")", parent, node);
    }

    protected void visitMethod(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        final String receiver = receiverType(node.getChildByFieldName("receiver"));
        final String leaf = (receiver.isEmpty() ? "" : receiver + ".") + textOf(name);
        element(ElementKind.METHOD, flatten(leaf) + "(" + signature(node.getChildByFieldName("parameters")) + ")", parent, node);
    }

    /**
     * The receiver type of a method, without a leading pointer star, e.g. {@code Point} for
     * {@code (p *Point)}.
     */
    protected String receiverType(final TSNode receiver) {
        if (receiver.isNull()) {
            return "";
        }
        final TSNode decl = firstChildOfType(receiver, "parameter_declaration");
        if (decl == null) {
            return "";
        }
        return textOf(decl.getChildByFieldName("type")).replaceFirst("^\\*", "");
    }

    protected void visitVariables(final TSNode node, final Element parent) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode spec = node.getNamedChild(i);
            if (!spec.getType().endsWith("_spec")) {
                continue;
            }
            for (int j = 0; j < spec.getNamedChildCount(); j++) {
                final TSNode c = spec.getNamedChild(j);
                if (c.getType().equals("identifier")) {
                    element(ElementKind.FIELD, flatten(textOf(c)), parent, node);
                }
            }
        }
    }

    protected String signature(final TSNode parameters) {
        if (parameters == null || parameters.isNull()) {
            return "";
        }
        final List<String> types = new ArrayList<>();
        for (int i = 0; i < parameters.getNamedChildCount(); i++) {
            final TSNode p = parameters.getNamedChild(i);
            if (p.getType().equals("parameter_declaration")) {
                types.add(escape(textOf(p.getChildByFieldName("type")).replaceAll("\\s+", "")));
            }
        }
        return String.join(",", types);
    }
}
