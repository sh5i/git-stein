package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a shell (bash) file: function definitions become methods, and top-level variable
 * assignments become fields. Function bodies are not descended into, so assignments local to a
 * function are not mistaken for fields.
 */
public class BashAnalyzer extends LanguageAnalyzer {
    public BashAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected void run() {
        for (int i = 0; i < treeRoot.getNamedChildCount(); i++) {
            final TSNode child = treeRoot.getNamedChild(i);
            switch (child.getType()) {
                case "function_definition" -> {
                    final TSNode name = child.getChildByFieldName("name");
                    if (!name.isNull()) {
                        element(ElementKind.METHOD, flatten(textOf(name)) + "()", root, child);
                    }
                }
                case "variable_assignment" -> {
                    final TSNode name = child.getChildByFieldName("name");
                    if (!name.isNull()) {
                        element(ElementKind.FIELD, flatten(textOf(name)), root, child);
                    }
                }
                default -> { }
            }
        }
    }
}
