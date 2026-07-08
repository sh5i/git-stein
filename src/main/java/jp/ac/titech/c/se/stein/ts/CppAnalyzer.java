package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a C or C++ file: classes (class/struct/union/enum), functions with a body (free, member,
 * and out-of-line {@code Class::method} definitions), and data members and namespace/file-scope
 * variables. Namespaces are naming scopes only; function bodies are not descended into. Names use the
 * {@code ::} scope operator flattened to {@code .} to stay portable across file systems.
 */
public class CppAnalyzer extends LanguageAnalyzer {
    public CppAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
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
                case "class_specifier", "struct_specifier", "union_specifier" -> visitType(child, child, parent);
                case "enum_specifier" -> visitEnum(child, child, parent);
                case "function_definition" -> visitFunction(child, child, parent);
                case "template_declaration" -> visitTemplate(child, parent);
                case "field_declaration" -> visitField(child, parent);
                case "declaration" -> visitDeclaration(child, parent);
                case "namespace_definition" -> visitNamespace(child, parent);
                // descend into linkage_specification (extern "C"), preprocessor blocks, etc.
                default -> {
                    if (!child.isError()) {
                        walk(child, parent);
                    }
                }
            }
        }
    }

    /**
     * Visits a namespace: a naming scope we descend into but never emit as an element (it would span
     * whole files). An anonymous namespace is transparent.
     */
    protected void visitNamespace(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        final Element scope = name.isNull() ? parent : element(ElementKind.CLASS, flatten(textOf(name)), parent, null);
        final TSNode body = node.getChildByFieldName("body");
        if (!body.isNull()) {
            walk(body, scope);
        }
    }

    /**
     * Visits a class/struct/union. {@code extent} covers the whole extracted range (including a
     * template header); {@code def} is the specifier itself. An anonymous type is transparent.
     */
    protected void visitType(final TSNode extent, final TSNode def, final Element parent) {
        final TSNode name = def.getChildByFieldName("name");
        final TSNode body = def.getChildByFieldName("body");
        if (name.isNull()) {
            if (!body.isNull()) {
                walk(body, parent);
            }
            return;
        }
        final Element klass = element(ElementKind.CLASS, flatten(textOf(name)), parent, extent);
        if (!body.isNull()) {
            walk(body, klass);
        }
    }

    protected void visitEnum(final TSNode extent, final TSNode def, final Element parent) {
        final TSNode name = def.getChildByFieldName("name");
        if (!name.isNull()) {
            element(ElementKind.CLASS, flatten(textOf(name)), parent, extent);
        }
    }

    /**
     * Unwraps a template declaration, visiting the class or function it wraps with the template
     * header included in the extent.
     */
    protected void visitTemplate(final TSNode node, final Element parent) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            switch (child.getType()) {
                case "class_specifier", "struct_specifier", "union_specifier" -> {
                    visitType(node, child, parent);
                    return;
                }
                case "enum_specifier" -> {
                    visitEnum(node, child, parent);
                    return;
                }
                case "function_definition" -> {
                    visitFunction(node, child, parent);
                    return;
                }
                default -> { }
            }
        }
    }

    protected void visitFunction(final TSNode extent, final TSNode def, final Element parent) {
        final TSNode fd = functionDeclarator(def.getChildByFieldName("declarator"));
        if (fd == null) {
            return;
        }
        final TSNode name = fd.getChildByFieldName("declarator");
        if (name.isNull()) {
            return;
        }
        final String signature = signature(fd.getChildByFieldName("parameters"));
        element(ElementKind.METHOD, flatten(textOf(name)) + "(" + signature + ")", parent, extent);
    }

    /**
     * Visits a class-body field declaration: one field element per declared data member. A member
     * function prototype (no body) is skipped, since the implemented definition is what carries the
     * history.
     */
    protected void visitField(final TSNode node, final Element parent) {
        if (functionDeclarator(node.getChildByFieldName("declarator")) != null) {
            return;
        }
        for (final TSNode name : fieldNames(node)) {
            element(ElementKind.FIELD, flatten(textOf(name)), parent, node);
        }
    }

    /**
     * Visits a namespace/file-scope declaration: one field element per declared variable. Function
     * prototypes, typedefs, and using-declarations have no init-declarator and are skipped.
     */
    protected void visitDeclaration(final TSNode node, final Element parent) {
        if (functionDeclarator(node.getChildByFieldName("declarator")) != null) {
            return;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (!"declarator".equals(node.getFieldNameForChild(i))) {
                continue;
            }
            final TSNode d = node.getChild(i);
            final TSNode inner = d.getType().equals("init_declarator") ? d.getChildByFieldName("declarator") : d;
            final TSNode name = declaratorName(inner);
            if (name != null) {
                element(ElementKind.FIELD, flatten(textOf(name)), parent, node);
            }
        }
    }

    /**
     * Unwraps pointer and reference declarators (e.g. the {@code *} of a pointer return type) to find
     * the function declarator, or null if there is none.
     */
    protected TSNode functionDeclarator(final TSNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.getType().equals("function_declarator") ? node
                : functionDeclarator(node.getChildByFieldName("declarator"));
    }

    /**
     * Finds the innermost name of a declarator, descending through pointer, reference, and array
     * declarators.
     */
    protected TSNode declaratorName(final TSNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        switch (node.getType()) {
            case "identifier", "field_identifier", "qualified_identifier" -> {
                return node;
            }
            default -> {
                final TSNode d = node.getChildByFieldName("declarator");
                if (d != null && !d.isNull()) {
                    return declaratorName(d);
                }
                // some declarators (e.g. a reference declarator) hold their inner name unnamed
                for (int i = 0; i < node.getNamedChildCount(); i++) {
                    final TSNode r = declaratorName(node.getNamedChild(i));
                    if (r != null) {
                        return r;
                    }
                }
                return null;
            }
        }
    }

    /**
     * Collects the {@code field_identifier} names declared by a data-member field declaration,
     * descending through pointer and array declarators to catch each declared name.
     */
    protected List<TSNode> fieldNames(final TSNode node) {
        final List<TSNode> result = new ArrayList<>();
        collectFieldNames(node, result);
        return result;
    }

    protected void collectFieldNames(final TSNode node, final List<TSNode> out) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            if (child.getType().equals("field_identifier")) {
                out.add(child);
            } else if (child.getType().endsWith("declarator")) {
                collectFieldNames(child, out);
            }
        }
    }

    /**
     * Renders a parameter as its type, dropping the parameter name and any default value.
     */
    protected String signature(final TSNode parameters) {
        if (parameters.isNull()) {
            return "";
        }
        final List<String> types = new ArrayList<>();
        for (int i = 0; i < parameters.getNamedChildCount(); i++) {
            final TSNode p = parameters.getNamedChild(i);
            switch (p.getType()) {
                case "parameter_declaration", "optional_parameter_declaration" -> types.add(parameterType(p));
                case "variadic_parameter_declaration" -> types.add("...");
                default -> {
                    if (textOf(p).equals("...")) {
                        types.add("...");
                    }
                }
            }
        }
        return String.join(",", types);
    }

    protected String parameterType(final TSNode p) {
        final TSNode name = declaratorName(p.getChildByFieldName("declarator"));
        final String content = text.getContent();
        String type;
        if (name != null && !name.isNull()) {
            type = content.substring(text.toCharIndex(p.getStartByte()), text.toCharIndex(name.getStartByte()))
                    + content.substring(text.toCharIndex(name.getEndByte()), text.toCharIndex(p.getEndByte()));
        } else {
            type = textOf(p);
        }
        final int eq = type.indexOf('=');
        return escape(eq >= 0 ? type.substring(0, eq) : type);
    }

    @Override
    protected String rawContentOf(final TSNode node) {
        final int beginLine = node.getStartPoint().getRow() + 1;
        final int endLine = node.getEndPoint().getRow() + 1;
        return text.getFragmentOfLines(beginLine, endLine).getWiderContent();
    }
}
