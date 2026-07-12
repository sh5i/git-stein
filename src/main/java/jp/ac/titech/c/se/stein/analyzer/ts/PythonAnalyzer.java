package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import java.util.ArrayList;
import java.util.List;

import org.treesitter.TSNode;
import org.treesitter.TreeSitterPython;

import jp.ac.titech.c.se.stein.core.SourceEncoding;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;

/**
 * A query-based analyzer for Python: detection is the declarative {@link #QUERY}
 * (classes, functions, and single-name assignments that are direct members of a module or a class
 * body), and only naming (parameter-name signatures) stays imperative. A decorated definition is
 * captured on its {@code decorated_definition} node so its extent includes the decorators, while its
 * name and parameters come from the inner definition.
 */
public class PythonAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (module (function_definition name: (identifier) @name parameters: (parameters) @params) @method)
            (block (function_definition name: (identifier) @name parameters: (parameters) @params) @method)
            (module (class_definition name: (identifier) @name) @class)
            (block (class_definition name: (identifier) @name) @class)
            (decorated_definition
                definition: (function_definition name: (identifier) @name parameters: (parameters) @params)) @method
            (decorated_definition definition: (class_definition name: (identifier) @name)) @class
            (module (expression_statement (assignment left: (identifier) @name)) @field)
            (class_definition body: (block (expression_statement (assignment left: (identifier) @name)) @field))
            """;

    public PythonAnalyzer() {
        super("Python", new NameFilter(true, "*.py"), SourceEncoding::decodeWithMagicComment, TreeSitterPython::new, QUERY);
    }

    @Override
    protected Signature signature(final TreeSitterModel m, final Element.Kind kind, final TSNode node, final Captures captures) {
        final String label = m.textOf(captures.get("name"));
        if (kind != Element.Kind.METHOD) {
            return Signature.of(label);
        }
        return new Signature(label, null, generateSignature(m, captures.get("params")));
    }

    /**
     * Generates a signature from parameter names, dropping type annotations and default values.
     */
    protected List<String> generateSignature(final TreeSitterModel m, final TSNode parameters) {
        if (parameters == null || parameters.isNull()) {
            return List.of();
        }
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < parameters.getNamedChildCount(); i++) {
            final TSNode child = parameters.getNamedChild(i);
            if (child.isExtra()) {
                // an extra node such as a comment, not a parameter
                continue;
            }
            final String param = m.textOf(child);
            final int cut = param.indexOf(':') >= 0 ? param.indexOf(':')
                    : param.indexOf('=') >= 0 ? param.indexOf('=') : param.length();
            final String name = param.substring(0, cut).trim();
            if (!name.isEmpty()) {
                names.add(m.escape(name));
            }
        }
        return names;
    }
}
