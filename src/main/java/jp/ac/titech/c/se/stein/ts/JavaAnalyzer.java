package jp.ac.titech.c.se.stein.ts;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TreeSitterJava;

import jp.ac.titech.c.se.stein.core.SourceText;

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

    public JavaAnalyzer(final String filename, final SourceText text, final TSNode treeRoot) {
        super(filename, text, treeRoot);
    }

    @Override
    protected TSLanguage grammar() {
        return new TreeSitterJava();
    }

    @Override
    protected String queryString() {
        return QUERY;
    }

    /**
     * Rejects anything inside a {@code class_body} that belongs to an anonymous class or an enum
     * constant rather than a type declaration: the visitor never descends into such a body (it treats
     * it like an anonymous class), so e.g. the {@code shine()} of {@code enum Color { GREEN { void
     * shine() {} } }} is not a member of {@code Color}.
     */
    @Override
    protected boolean accept(final ElementKind kind, final TSNode node, final Captures captures) {
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
    protected String name(final ElementKind kind, final TSNode node, final Captures captures) {
        return kind == ElementKind.METHOD ? generateMethodName(node) : textOf(captures.get("name"));
    }

    @Override
    protected boolean isFunctionNode(final TSNode node) {
        return switch (node.getType()) {
            case "method_declaration", "constructor_declaration", "compact_constructor_declaration" -> true;
            default -> false;
        };
    }

    /**
     * Generates a FinerGit-compatible method name: {@code [typeParams]_name(paramTypes)}, mirroring
     * HistorageJdt's MethodNameGenerator.
     */
    protected String generateMethodName(final TSNode node) {
        final StringBuilder sb = new StringBuilder();
        final TSNode typeParameters = childOfType(node, "type_parameters");
        if (typeParameters != null && typeParameters.getNamedChildCount() > 0) {
            final List<String> names = new ArrayList<>();
            for (int i = 0; i < typeParameters.getNamedChildCount(); i++) {
                names.add(escapeType(typeParameterText(typeParameters.getNamedChild(i))));
            }
            sb.append("[").append(String.join(",", names)).append("]_");
        }
        sb.append(textOf(node.getChildByFieldName("name")));
        final List<String> params = new ArrayList<>();
        final TSNode parameters = node.getChildByFieldName("parameters");
        if (!parameters.isNull()) {
            if (parameters.hasError()) {
                // the grammar rejects some parameter forms (e.g., annotated varargs
                // "Object @Nullable ... params"); reconstruct the types from the text
                params.addAll(parameterTypeNamesFromText(textOf(parameters)));
            } else {
                for (int i = 0; i < parameters.getNamedChildCount(); i++) {
                    final TSNode p = parameters.getNamedChild(i);
                    switch (p.getType()) {
                        case "formal_parameter" -> params.add(parameterTypeName(p));
                        case "spread_parameter" -> params.add(spreadParameterTypeName(p));
                        default -> { } // receiver parameters and comments are not parameters
                    }
                }
            }
        }
        sb.append("(").append(String.join(",", params)).append(")");
        return sb.toString();
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
    protected String typeParameterText(final TSNode tp) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tp.getNamedChildCount(); i++) {
            final TSNode child = tp.getNamedChild(i);
            if (child.getType().endsWith("annotation")) {
                sb.append(collapse(textOf(child))).append(" ");
            } else if (child.getType().equals("type_bound")) {
                sb.append(" extends ");
                final List<String> bounds = new ArrayList<>();
                for (int j = 0; j < child.getNamedChildCount(); j++) {
                    bounds.add(typeText(child.getNamedChild(j)));
                }
                sb.append(String.join(" & ", bounds));
            } else {
                sb.append(textOf(child));
            }
        }
        return sb.toString();
    }

    protected String parameterTypeName(final TSNode p) {
        String name = escapeType(typeText(p.getChildByFieldName("type")));
        final TSNode dimensions = childOfType(p, "dimensions"); // e.g., int a[]
        if (dimensions != null) {
            name += escapeType(dimensionsText(dimensions));
        }
        return name;
    }

    /**
     * Prints array dimensions the way JDT does: plain brackets adjacent, an annotated dimension as
     * {@code " @Anno []"}.
     */
    protected String dimensionsText(final TSNode dimensions) {
        final StringBuilder sb = new StringBuilder();
        boolean annotated = false;
        for (int i = 0; i < dimensions.getChildCount(); i++) {
            final TSNode child = dimensions.getChild(i);
            if (child.isNamed()) {
                sb.append(" ").append(collapse(textOf(child)));
                annotated = true;
            } else if (textOf(child).equals("[")) {
                sb.append(annotated ? " []" : "[]");
                annotated = false;
            }
        }
        return sb.toString();
    }

    protected String spreadParameterTypeName(final TSNode p) {
        for (int i = 0; i < p.getNamedChildCount(); i++) {
            final TSNode child = p.getNamedChild(i);
            if (!child.getType().equals("modifiers") && !child.getType().equals("variable_declarator")) {
                return escapeType(typeText(child)) + "...";
            }
        }
        return "...";
    }

    /**
     * Prints a type the way JDT's AST flattener does (e.g., no spaces inside type arguments), so that
     * the generated names match HistorageJdt.
     */
    protected String typeText(final TSNode type) {
        if (type.isNull()) {
            return "";
        }
        switch (type.getType()) {
            case "generic_type" -> {
                final StringBuilder sb = new StringBuilder(typeText(type.getChild(0)));
                final TSNode arguments = childOfType(type, "type_arguments");
                sb.append("<");
                if (arguments != null) {
                    final List<String> args = new ArrayList<>();
                    for (int i = 0; i < arguments.getNamedChildCount(); i++) {
                        args.add(typeText(arguments.getNamedChild(i)));
                    }
                    sb.append(String.join(",", args));
                }
                sb.append(">");
                return sb.toString();
            }
            case "array_type" -> {
                return typeText(type.getChildByFieldName("element"))
                        + dimensionsText(type.getChildByFieldName("dimensions"));
            }
            case "wildcard" -> {
                // e.g. ?, ? extends T, @Nullable ? super T
                final StringBuilder sb = new StringBuilder();
                for (int i = 0; i < type.getChildCount(); i++) {
                    final TSNode child = type.getChild(i);
                    final String token = textOf(child);
                    if (child.getType().endsWith("annotation")) {
                        sb.append(collapse(token)).append(" ");
                    } else if (token.equals("?")) {
                        sb.append("?");
                    } else if (token.equals("extends") || token.equals("super")) {
                        sb.append(" ").append(token).append(" ");
                    } else {
                        sb.append(typeText(child));
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
                        sb.append(textOf(child));
                    } else if (child.getType().endsWith("annotation")) {
                        sb.append(collapse(textOf(child))).append(" ");
                    } else {
                        sb.append(typeText(child));
                    }
                }
                return sb.toString();
            }
            default -> {
                return collapse(textOf(type));
            }
        }
    }

    /**
     * The escaping HistorageJdt's MethodNameGenerator applies to type names.
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

    /**
     * Extracts the source of the given declaration with its surrounding comments, like HistorageJdt's
     * fragment extraction: the leading comment run directly above (except comments that trail the
     * previous sibling on its own line) and the trailing comments on the same line as the declaration
     * end.
     */
    @Override
    protected String rawContentOf(final TSNode node) {
        final int begin = text.toCharIndex(attachedStart(node));
        final int end = text.toCharIndex(attachedEnd(node));
        return text.getFragment(begin, end).getWiderContent();
    }

    protected boolean isComment(final TSNode node) {
        return node.getType().equals("line_comment") || node.getType().equals("block_comment");
    }

    /**
     * The start of the leading comment run, following JDT. A doc comment directly preceding the
     * declaration is bound to it regardless of blank lines, like the Eclipse parser binds Javadoc.
     * Above that, comments chain upward as long as no blank line intervenes, as in JDT's
     * {@code DefaultCommentMapper}; a comment starting on the same line as the previous sibling's end
     * (for the first sibling: the file start) trails that sibling instead and stops the chain.
     */
    protected int attachedStart(final TSNode node) {
        TSNode prev = node.getPrevSibling();
        int start = node.getStartByte();
        int startRow = node.getStartPoint().getRow();
        if (!prev.isNull() && isComment(prev) && textOf(prev).startsWith("/**")) {
            start = prev.getStartByte();
            startRow = prev.getStartPoint().getRow();
            prev = prev.getPrevSibling();
        }
        final int nodeStartRow = startRow;
        int previousEndRow = 0;
        {
            TSNode p = prev;
            while (!p.isNull() && isComment(p)) {
                p = p.getPrevSibling();
            }
            if (!p.isNull()) {
                previousEndRow = p.getEndPoint().getRow();
            }
        }
        while (!prev.isNull() && isComment(prev)) {
            final int commentRow = prev.getStartPoint().getRow();
            if (startRow - prev.getEndPoint().getRow() > 1) {
                break; // a blank line between the comment and what follows it
            }
            if (commentRow == previousEndRow && commentRow != nodeStartRow) {
                break; // trails the previous sibling
            }
            start = prev.getStartByte();
            startRow = commentRow;
            prev = prev.getPrevSibling();
        }
        return start;
    }

    /**
     * The end of the trailing comment run, following JDT's {@code DefaultCommentMapper}: comments
     * chain downward until a blank line; unless the declaration is the last member, the run must be
     * separated from the next declaration by a blank line, or only the comments on the declaration's
     * own end line trail it.
     */
    protected int attachedEnd(final TSNode node) {
        final int nodeEndRow = node.getEndPoint().getRow();
        int end = node.getEndByte();
        int endRow = nodeEndRow;
        int sameLineEnd = -1;
        TSNode next = node.getNextSibling();
        while (!next.isNull() && isComment(next)) {
            if (next.getStartPoint().getRow() - endRow > 1) {
                break; // a blank line between the previous end and the comment
            }
            end = next.getEndByte();
            endRow = next.getEndPoint().getRow();
            if (next.getStartPoint().getRow() == nodeEndRow) {
                sameLineEnd = end;
            }
            next = next.getNextSibling();
        }
        if (end == node.getEndByte()) {
            return end;
        }
        // unless this is the last member (followed by a closing token), the run trails this
        // declaration only when a blank line separates it from the next declaration
        if (!next.isNull() && next.isNamed() && next.getStartPoint().getRow() - endRow <= 1) {
            return sameLineEnd != -1 ? sameLineEnd : node.getEndByte();
        }
        return end;
    }
}
