package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;

import jp.ac.titech.c.se.stein.core.SourceText;

/**
 * Analyzes a Python file: classes and functions become elements, and a top-level or class-body
 * assignment to a single name becomes a field. Descends into class bodies and statement blocks, but
 * not into function bodies, so nested functions stay inside their enclosing function's element.
 */
public class PythonAnalyzer extends LanguageAnalyzer {
    public PythonAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected void run() {
        walk(treeRoot, root, true);
    }

    /**
     * Walks the children of a node. {@code direct} tells whether they are directly at the top level
     * of the file or of a class body, which is where fields are defined.
     */
    protected void walk(final TSNode node, final Element parent, final boolean direct) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            switch (child.getType()) {
                case "class_definition" -> visitClass(child, child, parent);
                case "function_definition" -> visitFunction(child, child, parent);
                case "decorated_definition" -> {
                    final TSNode def = child.getChildByFieldName("definition");
                    if (!def.isNull() && def.getType().equals("class_definition")) {
                        visitClass(child, def, parent);
                    } else if (!def.isNull() && def.getType().equals("function_definition")) {
                        visitFunction(child, def, parent);
                    }
                }
                case "expression_statement" -> {
                    if (direct) {
                        visitField(child, parent);
                    }
                }
                // do not descend into ERROR subtrees; descend into if/try/with etc.
                default -> {
                    if (!child.isError()) {
                        walk(child, parent, false);
                    }
                }
            }
        }
    }

    /**
     * Visits a class definition. {@code extent} covers the whole extracted range including
     * decorators; {@code def} is the class_definition node itself.
     */
    protected void visitClass(final TSNode extent, final TSNode def, final Element parent) {
        final String name = textOf(def.getChildByFieldName("name"));
        final Element klass = element(ElementKind.CLASS, name, parent, extent);
        final TSNode body = def.getChildByFieldName("body");
        if (!body.isNull()) {
            walk(body, klass, true);
        }
    }

    /**
     * Visits a function definition; does not descend into its body, so nested functions are kept
     * inside their enclosing function's element.
     */
    protected void visitFunction(final TSNode extent, final TSNode def, final Element parent) {
        final String name = textOf(def.getChildByFieldName("name"));
        final String signature = generateSignature(def.getChildByFieldName("parameters"));
        element(ElementKind.METHOD, name + "(" + signature + ")", parent, extent);
    }

    /**
     * Visits a top-level statement of a file or class body; a plain assignment to a single name
     * becomes a field element.
     */
    protected void visitField(final TSNode statement, final Element parent) {
        if (statement.getNamedChildCount() == 0) {
            return;
        }
        final TSNode assignment = statement.getNamedChild(0);
        if (!assignment.getType().equals("assignment")) {
            return;
        }
        final TSNode left = assignment.getChildByFieldName("left");
        if (!left.isNull() && left.getType().equals("identifier")) {
            element(ElementKind.FIELD, textOf(left), parent, statement);
        }
    }

    /**
     * Generates a signature from parameter names, dropping type annotations and default values.
     */
    protected String generateSignature(final TSNode parameters) {
        if (parameters.isNull()) {
            return "";
        }
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < parameters.getNamedChildCount(); i++) {
            final TSNode child = parameters.getNamedChild(i);
            if (child.isExtra()) {
                // an extra node such as a comment, not a parameter
                continue;
            }
            final String param = textOf(child);
            final int cut = param.indexOf(':') >= 0 ? param.indexOf(':')
                    : param.indexOf('=') >= 0 ? param.indexOf('=') : param.length();
            final String name = param.substring(0, cut).trim();
            if (!name.isEmpty()) {
                names.add(escape(name));
            }
        }
        return String.join(",", names);
    }

    /**
     * Extracts the full source lines of the given definition. The extent ends at the last meaningful
     * (non-comment) descendant, since tree-sitter blocks also hold the comments trailing after the
     * last statement.
     */
    @Override
    protected String rawContentOf(final TSNode node) {
        final int beginLine = node.getStartPoint().getRow() + 1;
        final int endLine = lastMeaningfulDescendant(node).getEndPoint().getRow() + 1;
        return text.getFragmentOfLines(beginLine, endLine).getWiderContent();
    }

    protected TSNode lastMeaningfulDescendant(final TSNode node) {
        TSNode last = node;
        while (true) {
            TSNode next = null;
            for (int i = last.getChildCount() - 1; i >= 0; i--) {
                final TSNode child = last.getChild(i);
                if (!child.isExtra() && !child.isMissing()) {
                    next = child;
                    break;
                }
            }
            if (next == null) {
                return last;
            }
            last = next;
        }
    }
}
