package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a Rust file: structs, enums, unions, and traits (classes, with struct fields and trait
 * methods split out), free functions, {@code impl} blocks (whose methods attach to the implemented
 * type), modules (naming scopes), and constants and statics (fields).
 */
public class RustAnalyzer extends LanguageAnalyzer {
    public RustAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
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
                case "struct_item", "enum_item", "union_item" -> visitType(child, parent);
                case "trait_item" -> visitTrait(child, parent);
                case "function_item" -> visitFunction(child, child, parent);
                case "impl_item" -> visitImpl(child, parent);
                case "mod_item" -> visitModule(child, parent);
                case "const_item", "static_item", "type_item" -> visitConst(child, parent);
                default -> {
                    if (!child.isError()) {
                        walk(child, parent);
                    }
                }
            }
        }
    }

    protected void visitType(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        if (name.isNull()) {
            return;
        }
        final Element klass = element(ElementKind.CLASS, flatten(textOf(name)), parent, node);
        final TSNode body = node.getChildByFieldName("body");
        if (!body.isNull()) {
            for (int i = 0; i < body.getNamedChildCount(); i++) {
                final TSNode f = body.getNamedChild(i);
                if (f.getType().equals("field_declaration")) {
                    element(ElementKind.FIELD, flatten(textOf(f.getChildByFieldName("name"))), klass, f);
                }
            }
        }
    }

    protected void visitTrait(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        if (name.isNull()) {
            return;
        }
        final Element klass = element(ElementKind.CLASS, flatten(textOf(name)), parent, node);
        final TSNode body = node.getChildByFieldName("body");
        if (!body.isNull()) {
            for (int i = 0; i < body.getNamedChildCount(); i++) {
                final TSNode m = body.getNamedChild(i);
                if (m.getType().equals("function_item") || m.getType().equals("function_signature_item")) {
                    visitFunction(m, m, klass);
                }
            }
        }
    }

    protected void visitImpl(final TSNode node, final Element parent) {
        final TSNode type = node.getChildByFieldName("type");
        final Element scope = type.isNull() ? parent
                : element(ElementKind.CLASS, flatten(baseTypeName(type)), parent, null);
        final TSNode body = node.getChildByFieldName("body");
        if (!body.isNull()) {
            for (int i = 0; i < body.getNamedChildCount(); i++) {
                final TSNode m = body.getNamedChild(i);
                if (m.getType().equals("function_item")) {
                    visitFunction(m, m, scope);
                }
            }
        }
    }

    protected void visitFunction(final TSNode extent, final TSNode def, final Element parent) {
        final TSNode name = def.getChildByFieldName("name");
        element(ElementKind.METHOD, flatten(textOf(name)) + "(" + signature(def.getChildByFieldName("parameters")) + ")", parent, extent);
    }

    protected void visitModule(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        final Element scope = name.isNull() ? parent : element(ElementKind.CLASS, flatten(textOf(name)), parent, null);
        final TSNode body = node.getChildByFieldName("body");
        if (!body.isNull()) {
            walk(body, scope);
        }
    }

    protected void visitConst(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        if (!name.isNull()) {
            element(ElementKind.FIELD, flatten(textOf(name)), parent, node);
        }
    }

    /**
     * The base name of a type, dropping generic arguments, e.g. {@code Foo} for {@code Foo<T>}.
     */
    protected String baseTypeName(final TSNode type) {
        if (type.getType().equals("generic_type")) {
            final TSNode base = firstChildOfType(type, "type_identifier");
            return base != null ? textOf(base) : textOf(type);
        }
        return textOf(type);
    }

    protected String signature(final TSNode parameters) {
        if (parameters == null || parameters.isNull()) {
            return "";
        }
        final List<String> types = new ArrayList<>();
        for (int i = 0; i < parameters.getNamedChildCount(); i++) {
            final TSNode p = parameters.getNamedChild(i);
            if (p.getType().equals("parameter")) {
                types.add(escape(textOf(p.getChildByFieldName("type")).replaceAll("\\s+", "")));
            } else if (p.getType().equals("self_parameter")) {
                types.add("self");
            }
        }
        return String.join(",", types);
    }
}
