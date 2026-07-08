package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterCSharp;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based analyzer for C#: detection is the declarative {@link #QUERY} (types, methods, and data
 * members), naming uses the scoped naming with FinerGit-style method signatures, and a regular
 * namespace is a {@code @scope} whose body nests its members by containment. The one case a query
 * cannot express — a file-scoped namespace ({@code namespace N;}) that scopes the siblings that follow
 * it rather than a body — is handled by {@link #postProcess}.
 */
public class CSharpAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (class_declaration name: (identifier) @name) @class
            (struct_declaration name: (identifier) @name) @class
            (interface_declaration name: (identifier) @name) @class
            (record_declaration name: (identifier) @name) @class
            (enum_declaration name: (identifier) @name) @class
            (namespace_declaration name: (_) @name) @scope
            (method_declaration) @method
            (constructor_declaration) @method
            (destructor_declaration) @method
            (operator_declaration) @method
            (field_declaration (variable_declaration (variable_declarator name: (identifier) @name))) @field
            (event_field_declaration (variable_declaration (variable_declarator name: (identifier) @name))) @field
            (property_declaration name: (identifier) @name) @field
            """;

    public CSharpAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterCSharp();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    @Override
    protected String name(final ElementKind kind, final TSNode node, final Captures captures) {
        return kind == ElementKind.METHOD ? methodName(node) : flatten(textOf(captures.get("name")));
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

    @Override
    protected String rawContentOf(final TSNode node) {
        final int beginLine = node.getStartPoint().getRow() + 1;
        final int endLine = node.getEndPoint().getRow() + 1;
        return text.getFragmentOfLines(beginLine, endLine).getWiderContent();
    }

    /**
     * A file-scoped namespace scopes the siblings that follow its declaration; move them under it.
     */
    @Override
    protected void postProcess() {
        final TSNode fileScoped = firstChildOfType(treeRoot, "file_scoped_namespace_declaration");
        if (fileScoped == null) {
            return;
        }
        final TSNode name = fileScoped.getChildByFieldName("name");
        if (!name.isNull()) {
            reparentAfter(root, flatten(textOf(name)), fileScoped.getEndByte());
        }
    }
}
