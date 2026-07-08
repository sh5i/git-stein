package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a TypeScript file. TypeScript is a superset of JavaScript, so this reuses
 * {@link JsAnalyzer} and adds the TypeScript-only constructs: namespaces (naming scopes), interfaces
 * and enums (classes), abstract classes, type aliases (fields), and the {@code required}/
 * {@code optional} parameter wrappers. Parameter types are dropped, leaving parameter names in the
 * signature.
 */
public class TsAnalyzer extends JsAnalyzer {
    public TsAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected boolean dispatch(final TSNode extent, final TSNode def, final Element parent) {
        switch (def.getType()) {
            case "abstract_class_declaration" -> visitClass(extent, def, parent);
            case "interface_declaration" -> visitInterface(extent, def, parent);
            case "enum_declaration" -> visitType(extent, def, parent);
            case "type_alias_declaration" -> visitTypeAlias(extent, def, parent);
            case "internal_module", "module" -> visitNamespace(def, parent);
            default -> {
                return super.dispatch(extent, def, parent);
            }
        }
        return true;
    }

    /**
     * An interface is a class element whose members are its property signatures (fields) and method
     * signatures (methods).
     */
    protected void visitInterface(final TSNode extent, final TSNode def, final Element parent) {
        final TSNode name = def.getChildByFieldName("name");
        if (name.isNull()) {
            return;
        }
        final Element iface = element(ElementKind.CLASS, flatten(textOf(name)), parent, extent);
        final TSNode body = def.getChildByFieldName("body");
        if (body.isNull()) {
            return;
        }
        for (int i = 0; i < body.getNamedChildCount(); i++) {
            final TSNode member = body.getNamedChild(i);
            switch (member.getType()) {
                case "method_signature" -> visitMethod(member, member, iface);
                case "property_signature" -> visitField(member, iface);
                default -> { }
            }
        }
    }

    /**
     * An enum or other named type declaration becomes a class element without descending.
     */
    protected void visitType(final TSNode extent, final TSNode def, final Element parent) {
        final TSNode name = def.getChildByFieldName("name");
        if (!name.isNull()) {
            element(ElementKind.CLASS, flatten(textOf(name)), parent, extent);
        }
    }

    protected void visitTypeAlias(final TSNode extent, final TSNode def, final Element parent) {
        final TSNode name = def.getChildByFieldName("name");
        if (!name.isNull()) {
            element(ElementKind.FIELD, flatten(textOf(name)), parent, extent);
        }
    }

    /**
     * A namespace ({@code internal_module}) is a naming scope only, never emitted as an element.
     */
    protected void visitNamespace(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        final Element scope = name.isNull() ? parent : element(ElementKind.CLASS, flatten(textOf(name)), parent, null);
        final TSNode body = node.getChildByFieldName("body");
        if (!body.isNull()) {
            walk(body, scope);
        }
    }

    @Override
    protected String paramName(final TSNode p) {
        return switch (p.getType()) {
            case "required_parameter", "optional_parameter" -> paramName(p.getChildByFieldName("pattern"));
            default -> super.paramName(p);
        };
    }
}
