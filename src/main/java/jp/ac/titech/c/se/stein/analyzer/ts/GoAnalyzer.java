package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;
import org.treesitter.TreeSitterGo;


/**
 * A query-based analyzer for Go: detection is the declarative {@link #QUERY},
 * and only naming (receiver-prefixed method names, type-list signatures) stays imperative.
 */
public class GoAnalyzer extends QueryAnalyzer {
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

    public GoAnalyzer() {
        super(Language.GO, TreeSitterGo::new, QUERY);
    }

    /**
     * A {@code type} spec is a class only when it aliases a struct or interface; any other type alias
     * is a field.
     */
    @Override
    protected Element.Kind refineKind(final TreeSitterModel m, final Element.Kind kind, final TSNode node,
                                      final Captures captures) {
        if (kind != Element.Kind.CLASS) {
            return kind;
        }
        final TSNode type = captures.get("typekind");
        final String t = type == null ? "" : type.getType();
        return t.equals("struct_type") || t.equals("interface_type") ? Element.Kind.CLASS : Element.Kind.FIELD;
    }

    @Override
    protected Signature signature(final TreeSitterModel m, final Element.Kind kind, final TSNode node, final Captures captures) {
        if (kind != Element.Kind.METHOD) {
            return Signature.of(m.flatten(m.textOf(captures.get("name"))));
        }
        final String receiver = captures.has("receiver") ? receiverType(m, captures.get("receiver")) : "";
        final String leaf = (receiver.isEmpty() ? "" : receiver + ".") + m.textOf(captures.get("name"));
        return new Signature(m.flatten(leaf), null, signature(m, captures.get("params")));
    }

    /**
     * The receiver type of a method, without a leading pointer star, e.g. {@code Point} for
     * {@code (p *Point)}.
     */
    protected String receiverType(final TreeSitterModel m, final TSNode receiver) {
        final TSNode decl = m.firstChildOfType(receiver, "parameter_declaration");
        if (decl == null) {
            return "";
        }
        return m.textOf(decl.getChildByFieldName("type")).replaceFirst("^\\*", "");
    }

    protected List<String> signature(final TreeSitterModel m, final TSNode parameters) {
        if (parameters == null || parameters.isNull()) {
            return List.of();
        }
        final List<String> types = new ArrayList<>();
        for (int i = 0; i < parameters.getNamedChildCount(); i++) {
            final TSNode p = parameters.getNamedChild(i);
            if (p.getType().equals("parameter_declaration")) {
                types.add(m.escape(m.textOf(p.getChildByFieldName("type")).replaceAll("\\s+", "")));
            }
        }
        return types;
    }
}
