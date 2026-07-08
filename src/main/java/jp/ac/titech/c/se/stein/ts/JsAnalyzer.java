package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a JavaScript file: classes, functions (function declarations, class methods, and arrow or
 * function expressions bound to a variable), and fields (class fields and non-function top-level
 * bindings). Function bodies are not descended into, and object-literal methods are not extracted.
 * Since JavaScript is untyped, a signature lists parameter names.
 */
public class JsAnalyzer extends LanguageAnalyzer {
    public JsAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected void run() {
        walk(treeRoot, root);
    }

    protected void walk(final TSNode node, final Element parent) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            if (child.getType().equals("export_statement")) {
                visitExport(child, parent);
            } else if (!dispatch(child, child, parent) && !child.isError()) {
                walk(child, parent);
            }
        }
    }

    /**
     * Dispatches a declaration to its visitor, using {@code extent} as the content range (which
     * differs from {@code def} when unwrapping an export). Returns whether it was handled; subclasses
     * override to add language constructs and delegate the rest to {@code super}.
     */
    protected boolean dispatch(final TSNode extent, final TSNode def, final Element parent) {
        switch (def.getType()) {
            case "class_declaration" -> visitClass(extent, def, parent);
            case "function_declaration", "generator_function_declaration" -> visitMethod(extent, def, parent);
            case "lexical_declaration", "variable_declaration" -> visitDeclaration(extent, def, parent);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Unwraps an export statement, visiting the declaration it exports with the {@code export} keyword
     * included in the extent. An anonymous default export or a re-export has nothing named to extract.
     */
    protected void visitExport(final TSNode node, final Element parent) {
        final TSNode decl = node.getChildByFieldName("declaration");
        if (!decl.isNull()) {
            dispatch(node, decl, parent);
        }
    }

    protected void visitClass(final TSNode extent, final TSNode def, final Element parent) {
        final TSNode name = def.getChildByFieldName("name");
        if (name.isNull()) {
            return;
        }
        final Element klass = element(ElementKind.CLASS, flatten(textOf(name)), parent, extent);
        final TSNode body = def.getChildByFieldName("body");
        if (body.isNull()) {
            return;
        }
        for (int i = 0; i < body.getNamedChildCount(); i++) {
            final TSNode member = body.getNamedChild(i);
            switch (member.getType()) {
                case "method_definition" -> visitMethod(member, member, klass);
                case "field_definition", "public_field_definition" -> visitField(member, klass);
                default -> { }
            }
        }
    }

    protected void visitMethod(final TSNode extent, final TSNode def, final Element parent) {
        final TSNode name = def.getChildByFieldName("name");
        if (!name.isNull()) {
            element(ElementKind.METHOD, flatten(textOf(name)) + "(" + signature(def) + ")", parent, extent);
        }
    }

    protected void visitField(final TSNode node, final Element parent) {
        // a JavaScript field_definition names it "property"; a TypeScript field/signature "name"
        TSNode name = node.getChildByFieldName("property");
        if (name.isNull()) {
            name = node.getChildByFieldName("name");
        }
        if (!name.isNull()) {
            element(ElementKind.FIELD, flatten(textOf(name)), parent, node);
        }
    }

    /**
     * A variable binding whose value is a function becomes a method element; any other binding becomes
     * a field element. Destructuring bindings (whose name is a pattern) are skipped.
     */
    protected void visitDeclaration(final TSNode extent, final TSNode def, final Element parent) {
        for (int i = 0; i < def.getNamedChildCount(); i++) {
            final TSNode declarator = def.getNamedChild(i);
            if (!declarator.getType().equals("variable_declarator")) {
                continue;
            }
            final TSNode name = declarator.getChildByFieldName("name");
            if (name.isNull() || !name.getType().equals("identifier")) {
                continue;
            }
            final TSNode value = declarator.getChildByFieldName("value");
            if (!value.isNull() && isFunction(value)) {
                element(ElementKind.METHOD, flatten(textOf(name)) + "(" + signature(value) + ")", parent, extent);
            } else {
                element(ElementKind.FIELD, flatten(textOf(name)), parent, extent);
            }
        }
    }

    protected boolean isFunction(final TSNode value) {
        return switch (value.getType()) {
            case "arrow_function", "function_expression", "generator_function" -> true;
            default -> false;
        };
    }

    /**
     * Renders a function's parameter names, dropping default values; a single unparenthesized arrow
     * parameter is held under a {@code parameter} field instead of {@code parameters}.
     */
    protected String signature(final TSNode fn) {
        final TSNode parameters = fn.getChildByFieldName("parameters");
        if (!parameters.isNull()) {
            final List<String> names = new ArrayList<>();
            for (int i = 0; i < parameters.getNamedChildCount(); i++) {
                names.add(paramName(parameters.getNamedChild(i)));
            }
            return String.join(",", names);
        }
        final TSNode single = fn.getChildByFieldName("parameter");
        return single.isNull() ? "" : paramName(single);
    }

    protected String paramName(final TSNode p) {
        if (p.getType().equals("assignment_pattern")) {
            return paramName(p.getChildByFieldName("left"));
        }
        return escape(textOf(p).replaceAll("\\s+", ""));
    }

    @Override
    protected String rawContentOf(final TSNode node) {
        final int beginLine = node.getStartPoint().getRow() + 1;
        final int endLine = node.getEndPoint().getRow() + 1;
        return text.getFragmentOfLines(beginLine, endLine).getWiderContent();
    }
}
