package jp.ac.titech.c.se.stein.analyzer.ts;

import jp.ac.titech.c.se.stein.analyzer.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.treesitter.TSNode;
import org.treesitter.TreeSitterJava;

import jp.ac.titech.c.se.stein.core.SourceEncoding;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;

/**
 * A query-based analyzer for Java: detection is the declarative {@link #QUERY} (class-like
 * declarations, methods/constructors, and one field per declarator, matched at any depth), while
 * naming applies the FinerGit method-name canonicalization and content attaches surrounding comments.
 * The "don't extract inside a method body, but do inside an initializer block" rule falls out of the
 * engine's generic containment: a class local to a method body nests under the method (a leaf) and is
 * dropped, while one in an initializer block nests under its class.
 */
public class JavaAnalyzer extends QueryAnalyzer {
    private static final String QUERY = """
            (class_declaration name: (identifier) @name) @class
            (interface_declaration name: (identifier) @name) @class
            (enum_declaration name: (identifier) @name) @class
            (annotation_type_declaration name: (identifier) @name) @class
            (record_declaration name: (identifier) @name) @class
            (method_declaration) @method
            (constructor_declaration) @method
            (compact_constructor_declaration) @method
            (field_declaration (variable_declarator name: (identifier) @name)) @field
            (constant_declaration (variable_declarator name: (identifier) @name)) @field
            """;

    public JavaAnalyzer() {
        super("Java", new NameFilter(true, "*.java"), SourceEncoding::decode, TreeSitterJava::new, QUERY);
    }

    /**
     * Rejects anything inside a {@code class_body} that belongs to an anonymous class or an enum
     * constant rather than a type declaration: the engine never descends into such a body (it treats
     * it like an anonymous class), so e.g. the {@code shine()} of {@code enum Color { GREEN { void
     * shine() {} } }} is not a member of {@code Color}.
     */
    @Override
    protected boolean accept(final TreeSitterModel m, final Element.Kind kind, final TSNode node, final Captures captures) {
        for (TSNode p = node.getParent(); p != null && !p.isNull(); p = p.getParent()) {
            if (p.getType().equals("class_body")) {
                final String owner = p.getParent().getType();
                if (owner.equals("enum_constant") || owner.equals("object_creation_expression")) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    protected Signature signature(final TreeSitterModel m, final Element.Kind kind, final TSNode node, final Captures captures) {
        if (kind != Element.Kind.METHOD) {
            return Signature.of(m.textOf(captures.get("name")));
        }
        return new Signature(m.textOf(node.getChildByFieldName("name")), methodTypeParameters(m, node),
                methodParameters(m, node));
    }

    @Override
    protected boolean isFunctionNode(final TSNode node) {
        return switch (node.getType()) {
            case "method_declaration", "constructor_declaration", "compact_constructor_declaration" -> true;
            default -> false;
        };
    }

    /**
     * The escaped, comma-joined generic type parameters of a method, or null when it has none; the
     * naming strategy wraps them as {@code [..]_}.
     */
    protected List<String> methodTypeParameters(final TreeSitterModel m, final TSNode node) {
        final TSNode typeParameters = childOfType(node, "type_parameters");
        if (typeParameters == null || typeParameters.getNamedChildCount() == 0) {
            return null;
        }
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < typeParameters.getNamedChildCount(); i++) {
            names.add(escapeType(typeParameterText(m, typeParameters.getNamedChild(i))));
        }
        return names;
    }

    /**
     * The escaped, comma-joined parameter types of a method (the empty string for a no-arg method),
     * mirroring how JDT renders them.
     */
    protected List<String> methodParameters(final TreeSitterModel m, final TSNode node) {
        final List<String> params = new ArrayList<>();
        final TSNode parameters = node.getChildByFieldName("parameters");
        if (!parameters.isNull()) {
            if (parameters.hasError()) {
                // the grammar rejects some parameter forms (e.g., annotated varargs
                // "Object @Nullable ... params"); reconstruct the types from the text
                params.addAll(parameterTypeNamesFromText(m.textOf(parameters)));
            } else {
                for (int i = 0; i < parameters.getNamedChildCount(); i++) {
                    final TSNode p = parameters.getNamedChild(i);
                    switch (p.getType()) {
                        case "formal_parameter" -> params.add(parameterTypeName(m, p));
                        case "spread_parameter" -> params.add(spreadParameterTypeName(m, p));
                        default -> { } // receiver parameters and comments are not parameters
                    }
                }
            }
        }
        return params;
    }

    /**
     * A trailing parameter name with optional array dimensions after it (e.g., {@code int a[]}).
     */
    private static final Pattern PARAMETER_NAME = Pattern.compile("\\s+(\\w+)\\s*((?:\\[\\s*\\])*)$");

    /**
     * Best-effort textual fallback for a parameter list the grammar could not parse: strips
     * annotations, final modifiers, and the parameter names, mirroring how JDT renders the types
     * (annotated varargs lose their annotation there too).
     */
    protected List<String> parameterTypeNamesFromText(final String parameters) {
        final List<String> result = new ArrayList<>();
        final String body = parameters.replaceAll("^\\(|\\)$", "");
        for (final String segment : splitTopLevel(body)) {
            String s = segment.replaceAll("@[\\w.]+(\\([^)]*\\))?", "");
            s = s.replaceAll("\\bfinal\\b", "");
            s = collapse(s);
            if (s.isEmpty() || s.equals("this") || s.endsWith(" this")) {
                continue; // a receiver parameter is not in the signature
            }
            final int ellipsis = s.indexOf("...");
            if (ellipsis >= 0) {
                s = s.substring(0, ellipsis).trim() + "...";
            } else {
                // drop the trailing parameter name, moving its dimensions to the type
                final Matcher m = PARAMETER_NAME.matcher(s);
                if (m.find()) {
                    s = s.substring(0, m.start()) + m.group(2).replaceAll("\\s+", "");
                }
            }
            result.add(escapeType(s.replaceAll("\\s*([<>,\\[\\]])\\s*", "$1")));
        }
        return result;
    }

    /**
     * Splits a parameter list at commas that are not nested in angle brackets or parentheses.
     */
    protected List<String> splitTopLevel(final String s) {
        final List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            switch (s.charAt(i)) {
                case '<', '(', '[' -> depth++;
                case '>', ')', ']' -> depth--;
                case ',' -> {
                    if (depth == 0) {
                        result.add(s.substring(start, i));
                        start = i + 1;
                    }
                }
                default -> { }
            }
        }
        if (start < s.length()) {
            result.add(s.substring(start));
        }
        return result;
    }

    /**
     * Prints a type parameter the way JDT does: {@code T extends Map<K,V> & Serializable}.
     */
    protected String typeParameterText(final TreeSitterModel m, final TSNode tp) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tp.getNamedChildCount(); i++) {
            final TSNode child = tp.getNamedChild(i);
            if (child.getType().endsWith("annotation")) {
                sb.append(collapse(m.textOf(child))).append(" ");
            } else if (child.getType().equals("type_bound")) {
                sb.append(" extends ");
                final List<String> bounds = new ArrayList<>();
                for (int j = 0; j < child.getNamedChildCount(); j++) {
                    bounds.add(typeText(m, child.getNamedChild(j)));
                }
                sb.append(String.join(" & ", bounds));
            } else {
                sb.append(m.textOf(child));
            }
        }
        return sb.toString();
    }

    protected String parameterTypeName(final TreeSitterModel m, final TSNode p) {
        String name = escapeType(typeText(m, p.getChildByFieldName("type")));
        final TSNode dimensions = childOfType(p, "dimensions"); // e.g., int a[]
        if (dimensions != null) {
            name += escapeType(dimensionsText(m, dimensions));
        }
        return name;
    }

    /**
     * Prints array dimensions the way JDT does: plain brackets adjacent, an annotated dimension as
     * {@code " @Anno []"}.
     */
    protected String dimensionsText(final TreeSitterModel m, final TSNode dimensions) {
        final StringBuilder sb = new StringBuilder();
        boolean annotated = false;
        for (int i = 0; i < dimensions.getChildCount(); i++) {
            final TSNode child = dimensions.getChild(i);
            if (child.isNamed()) {
                sb.append(" ").append(collapse(m.textOf(child)));
                annotated = true;
            } else if (m.textOf(child).equals("[")) {
                sb.append(annotated ? " []" : "[]");
                annotated = false;
            }
        }
        return sb.toString();
    }

    protected String spreadParameterTypeName(final TreeSitterModel m, final TSNode p) {
        for (int i = 0; i < p.getNamedChildCount(); i++) {
            final TSNode child = p.getNamedChild(i);
            if (!child.getType().equals("modifiers") && !child.getType().equals("variable_declarator")) {
                return escapeType(typeText(m, child)) + "...";
            }
        }
        return "...";
    }

    /**
     * Prints a type the way JDT's AST flattener does (e.g., no spaces inside type arguments), so that
     * the generated names match JdtAnalyzer's.
     */
    protected String typeText(final TreeSitterModel m, final TSNode type) {
        if (type.isNull()) {
            return "";
        }
        switch (type.getType()) {
            case "generic_type" -> {
                final StringBuilder sb = new StringBuilder(typeText(m, type.getChild(0)));
                final TSNode arguments = childOfType(type, "type_arguments");
                sb.append("<");
                if (arguments != null) {
                    final List<String> args = new ArrayList<>();
                    for (int i = 0; i < arguments.getNamedChildCount(); i++) {
                        args.add(typeText(m, arguments.getNamedChild(i)));
                    }
                    sb.append(String.join(",", args));
                }
                sb.append(">");
                return sb.toString();
            }
            case "array_type" -> {
                return typeText(m, type.getChildByFieldName("element"))
                        + dimensionsText(m, type.getChildByFieldName("dimensions"));
            }
            case "wildcard" -> {
                // e.g. ?, ? extends T, @Nullable ? super T
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < type.getChildCount(); i++) {
                    final TSNode child = type.getChild(i);
                    final String token = m.textOf(child);
                    if (child.getType().endsWith("annotation")) {
                        sb.append(collapse(token)).append(" ");
                    } else if (token.equals("?")) {
                        sb.append("?");
                    } else if (token.equals("extends") || token.equals("super")) {
                        sb.append(" ").append(token).append(" ");
                    } else {
                        sb.append(typeText(m, child));
                    }
                }
                return sb.toString();
            }
            case "scoped_type_identifier" -> {
                // e.g. A.B or A.@Nullable B; JDT prints a space after a type annotation
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < type.getChildCount(); i++) {
                    final TSNode child = type.getChild(i);
                    if (!child.isNamed()) {
                        sb.append(m.textOf(child));
                    } else if (child.getType().endsWith("annotation")) {
                        sb.append(collapse(m.textOf(child))).append(" ");
                    } else {
                        sb.append(typeText(m, child));
                    }
                }
                return sb.toString();
            }
            default -> {
                return collapse(m.textOf(type));
            }
        }
    }

    /**
     * The escaping JDT-compatible method naming applies to type names.
     */
    protected String escapeType(final String s) {
        return s.replace(' ', '-')
                .replace('?', '#')
                .replace('<', '[')
                .replace('>', ']');
    }

    protected String collapse(final String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    protected TSNode childOfType(final TSNode node, final String type) {
        for (int i = 0; i < node.getNamedChildCount(); i++) {
            final TSNode child = node.getNamedChild(i);
            if (child.getType().equals(type)) {
                return child;
            }
        }
        return null;
    }

    @Override
    protected boolean isComment(final TSNode node) {
        return node.getType().equals("line_comment") || node.getType().equals("block_comment");
    }
}
