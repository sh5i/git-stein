package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a C# file: types (class/struct/interface/record/enum), methods (methods, constructors,
 * destructors, and operators), and data members (fields, properties, and events). Namespaces are
 * naming scopes only; method bodies are not descended into. Property and event declarations are
 * treated as fields. Names use the scoped naming, escaped to stay portable across file systems.
 */
public class CSharpAnalyzer extends LanguageAnalyzer {
    public CSharpAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
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
                case "class_declaration", "struct_declaration", "interface_declaration",
                     "record_declaration", "record_struct_declaration" -> visitType(child, parent);
                case "enum_declaration" -> visitEnum(child, parent);
                case "method_declaration", "constructor_declaration", "destructor_declaration",
                     "operator_declaration" -> visitMethod(child, parent);
                case "field_declaration", "event_field_declaration" -> visitField(child, parent);
                case "property_declaration" -> visitProperty(child, parent);
                case "namespace_declaration" -> visitNamespace(child, parent);
                // a file-scoped namespace scopes every sibling that follows it
                case "file_scoped_namespace_declaration" -> parent = scopeOf(child, parent);
                default -> {
                    if (!child.isError()) {
                        walk(child, parent);
                    }
                }
            }
        }
    }

    protected void visitNamespace(final TSNode node, final Element parent) {
        final Element scope = scopeOf(node, parent);
        final TSNode body = node.getChildByFieldName("body");
        if (!body.isNull()) {
            walk(body, scope);
        }
    }

    /**
     * The naming scope of a namespace: never emitted as an element (it would span whole files). An
     * anonymous namespace is transparent.
     */
    protected Element scopeOf(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        return name.isNull() ? parent : element(ElementKind.CLASS, flatten(textOf(name)), parent, null);
    }

    protected void visitType(final TSNode node, final Element parent) {
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

    protected void visitEnum(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        if (!name.isNull()) {
            element(ElementKind.CLASS, flatten(textOf(name)), parent, node);
        }
    }

    protected void visitMethod(final TSNode node, final Element parent) {
        element(ElementKind.METHOD, methodName(node), parent, node);
    }

    /**
     * A field declaration (each variable declarator), a property, or an event becomes one field
     * element; properties and events are named members without a parameter signature.
     */
    protected void visitField(final TSNode node, final Element parent) {
        final TSNode declaration = childOfType(node, "variable_declaration");
        if (declaration == null) {
            return;
        }
        for (int i = 0; i < declaration.getNamedChildCount(); i++) {
            final TSNode declarator = declaration.getNamedChild(i);
            if (declarator.getType().equals("variable_declarator")) {
                final TSNode name = declarator.getChildByFieldName("name");
                if (!name.isNull()) {
                    element(ElementKind.FIELD, flatten(textOf(name)), parent, node);
                }
            }
        }
    }

    protected void visitProperty(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        if (!name.isNull()) {
            element(ElementKind.FIELD, flatten(textOf(name)), parent, node);
        }
    }

    /**
     * Generates a FinerGit-style method name {@code [typeParams]_name(paramTypes)}, prefixing a
     * destructor with {@code ~} and naming an operator {@code operator<symbol>}.
     */
    protected String methodName(final TSNode node) {
        final StringBuilder sb = new StringBuilder();
        final TSNode typeParameters = node.getChildByFieldName("type_parameters");
        if (typeParameters != null && !typeParameters.isNull()) {
            sb.append("[").append(typeParameters(typeParameters)).append("]_");
        }
        switch (node.getType()) {
            case "destructor_declaration" -> sb.append("~").append(textOf(node.getChildByFieldName("name")));
            case "operator_declaration" -> sb.append("operator").append(textOf(node.getChildByFieldName("operator")));
            default -> sb.append(textOf(node.getChildByFieldName("name")));
        }
        sb.append("(").append(signature(node.getChildByFieldName("parameters"))).append(")");
        return flatten(sb.toString());
    }

    protected String typeParameters(final TSNode list) {
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < list.getNamedChildCount(); i++) {
            final TSNode child = list.getNamedChild(i);
            if (child.getType().equals("type_parameter")) {
                names.add(textOf(child.getChildByFieldName("name")));
            }
        }
        return String.join(",", names);
    }

    /**
     * Renders each parameter as its declared type, dropping the parameter name; the enclosing
     * {@link #methodName} flattens any generic angle brackets into a portable form.
     */
    protected String signature(final TSNode parameters) {
        if (parameters.isNull()) {
            return "";
        }
        final List<String> types = new ArrayList<>();
        for (int i = 0; i < parameters.getNamedChildCount(); i++) {
            final TSNode p = parameters.getNamedChild(i);
            if (p.getType().equals("parameter")) {
                final TSNode type = p.getChildByFieldName("type");
                if (!type.isNull()) {
                    types.add(textOf(type).replaceAll("\\s+", ""));
                }
            }
        }
        return String.join(",", types);
    }

    protected TSNode childOfType(final TSNode node, final String type) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            if (child.getType().equals(type)) {
                return child;
            }
        }
        return null;
    }

    @Override
    protected String rawContentOf(final TSNode node) {
        final int beginLine = node.getStartPoint().getRow() + 1;
        final int endLine = node.getEndPoint().getRow() + 1;
        return text.getFragmentOfLines(beginLine, endLine).getWiderContent();
    }
}
