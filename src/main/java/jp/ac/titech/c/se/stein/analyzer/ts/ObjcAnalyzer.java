package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import org.treesitter.TSNode;
import org.treesitter.TreeSitterObjc;

import jp.ac.titech.c.se.stein.core.SourceEncoding;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;

/**
 * A query-based analyzer for Objective-C: detection is the declarative {@link #QUERY}
 * ({@code @interface}/{@code @implementation} blocks and their direct method and data members), and a
 * method is named by its selector. Members are matched only as direct children of a class node, so
 * declarations elsewhere (e.g. a {@code @protocol}'s methods) are left out.
 */
public class ObjcAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (class_interface name: (identifier) @name) @class
            (class_implementation name: (identifier) @name) @class
            (category_interface name: (identifier) @name) @class
            (category_implementation name: (identifier) @name) @class

            (class_interface (method_declaration) @method)
            (class_interface (method_definition) @method)
            (class_interface (property_declaration declarator: (_)) @field)
            (class_interface (field_declaration declarator: (_)) @field)

            (category_interface (method_declaration) @method)
            (category_interface (property_declaration declarator: (_)) @field)
            (category_interface (field_declaration declarator: (_)) @field)

            (class_implementation (method_definition) @method)
            (class_implementation (field_declaration declarator: (_)) @field)

            (category_implementation (method_definition) @method)
            """;

    public ObjcAnalyzer() {
        super("Objective-C", new NameFilter(true, "*.m", "*.mm"), SourceEncoding::decode, TreeSitterObjc::new, QUERY);
    }

    @Override
    protected Signature signature(final TreeSitterModel m, final Element.Kind kind, final TSNode node, final Captures captures) {
        if (kind == Element.Kind.METHOD) {
            // the selector already encodes the arguments (e.g. initWithFrame:), so it is the whole name
            return Signature.of(m.flatten(selectorName(m, node)));
        }
        if (kind == Element.Kind.FIELD) {
            // one field per declaration named by its first declarator; a declaration with several
            // declarators (e.g. size_t w, h) still yields a single field
            return Signature.of(m.flatten(m.textOf(node.getChildByFieldName("declarator"))));
        }
        return Signature.of(m.flatten(m.textOf(captures.get("name"))));
    }

    /**
     * The selector of a method: a keyword selector joins its keyword parts with colons
     * ({@code doThing:with:}), a unary selector is a bare name ({@code simple}).
     */
    protected String selectorName(final TreeSitterModel m, final TSNode method) {
        final TSNode selector = method.getChildByFieldName("selector");
        if (selector.isNull()) {
            return "";
        }
        if (!selector.getType().equals("keyword_selector")) {
            return m.textOf(selector);
        }
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < selector.getNamedChildCount(); i++) {
            final TSNode part = selector.getNamedChild(i);
            if (part.getType().equals("keyword_declarator")) {
                sb.append(m.textOf(part.getChildByFieldName("keyword"))).append(":");
            }
        }
        return sb.toString();
    }
}
