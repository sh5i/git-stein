package jp.ac.titech.c.se.stein.ts;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a shell (bash) file: function definitions become methods and top-level variable assignments
 * become fields. It descends into brace groups, subshells, and control structures (so functions inside
 * a wrapping {@code { ... }} block are still found) but not into function bodies, so a function's local
 * assignments are not mistaken for fields.
 */
public class BashAnalyzer extends LanguageAnalyzer {
    public BashAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected void run() {
        walk(treeRoot);
    }

    protected void walk(final TSNode node) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
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
                // a command is a statement leaf; descending it would pick up stray substitutions
                case "command", "comment" -> { }
                default -> {
                    if (!child.isError()) {
                        walk(child);
                    }
                }
            }
        }
    }
}
