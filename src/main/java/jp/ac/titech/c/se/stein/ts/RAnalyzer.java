package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes an R file: a name bound to a {@code function(...)} becomes a method; any other top-level
 * binding becomes a field. R has no class construct in common use, so none is extracted.
 */
public class RAnalyzer extends LanguageAnalyzer {
    public RAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
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
                case "left_assignment", "equals_assignment", "super_assignment" -> visitAssignment(child, parent);
                default -> {
                    if (!child.isError()) {
                        walk(child, parent);
                    }
                }
            }
        }
    }

    protected void visitAssignment(final TSNode node, final Element parent) {
        final TSNode name = node.getChildByFieldName("name");
        final TSNode value = node.getChildByFieldName("value");
        if (name.isNull()) {
            return;
        }
        if (!value.isNull() && value.getType().equals("function_definition")) {
            element(ElementKind.METHOD, flatten(textOf(name)) + "(" + signature(value) + ")", parent, node);
        } else {
            element(ElementKind.FIELD, flatten(textOf(name)), parent, node);
        }
    }

    protected String signature(final TSNode functionDefinition) {
        final TSNode params = firstChildOfType(functionDefinition, "formal_parameters");
        if (params == null) {
            return "";
        }
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < params.getNamedChildCount(); i++) {
            final TSNode child = params.getNamedChild(i);
            final TSNode name = child.getType().equals("identifier") ? child : firstChildOfType(child, "identifier");
            if (name != null) {
                names.add(escape(textOf(name).replaceAll("\\s+", "")));
            }
        }
        return String.join(",", names);
    }
}
