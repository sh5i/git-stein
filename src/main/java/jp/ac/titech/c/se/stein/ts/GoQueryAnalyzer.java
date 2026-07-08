package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterGo;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * A query-based reimplementation of the imperative Go visitor: detection is the declarative {@link #QUERY},
 * and only naming (receiver-prefixed method names, type-list signatures) stays imperative.
 */
public class GoQueryAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (const_declaration (const_spec name: (identifier) @name)) @field
            (var_declaration (var_spec name: (identifier) @name)) @field
            (type_declaration (type_spec name: (type_identifier) @name type: (_) @typekind)) @class
            (field_declaration (field_identifier) @name) @field
            (method_elem name: (field_identifier) @name parameters: (parameter_list) @params) @method
            (function_declaration name: (identifier) @name parameters: (parameter_list) @params) @method
            (method_declaration receiver: (parameter_list) @receiver
                name: (field_identifier) @name parameters: (parameter_list) @params) @method
            """;

    public GoQueryAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterGo();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    /**
     * A {@code type} spec is a class only when it aliases a struct or interface; any other type alias
     * is a field.
     */
    @Override
    protected ElementKind refineKind(final ElementKind kind, final TSNode node, final Captures captures) {
        if (kind != ElementKind.CLASS) {
            return kind;
        }
        final TSNode type = captures.get("typekind");
        final String t = type == null ? "" : type.getType();
        return t.equals("struct_type") || t.equals("interface_type") ? ElementKind.CLASS : ElementKind.FIELD;
    }

    @Override
    protected String name(final ElementKind kind, final TSNode node, final Captures captures) {
        if (kind != ElementKind.METHOD) {
            return flatten(textOf(captures.get("name")));
        }
        final String receiver = captures.has("receiver") ? receiverType(captures.get("receiver")) : "";
        final String leaf = (receiver.isEmpty() ? "" : receiver + ".") + textOf(captures.get("name"));
        return flatten(leaf) + "(" + signature(captures.get("params")) + ")";
    }

    /**
     * The receiver type of a method, without a leading pointer star, e.g. {@code Point} for
     * {@code (p *Point)}.
     */
    protected String receiverType(final TSNode receiver) {
        final TSNode decl = firstChildOfType(receiver, "parameter_declaration");
        if (decl == null) {
            return "";
        }
        return textOf(decl.getChildByFieldName("type")).replaceFirst("^\\*", "");
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
