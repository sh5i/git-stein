package jp.ac.titech.c.se.stein.app.blob;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.historage.FinerGitNaming;
import jp.ac.titech.c.se.stein.historage.Kind;
import jp.ac.titech.c.se.stein.historage.Module;
import jp.ac.titech.c.se.stein.historage.PythonNaming;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import jp.ac.titech.c.se.stein.util.PythonSource;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterPython;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * A Historage generator using tree-sitter, currently supporting Python and Java.
 * Splits source files into finer-grained modules (one file per class, function, or field).
 * Java files follow the same extraction semantics and FinerGit-compatible naming as
 * {@link HistorageJdt}; Python files use an analogous naming of its own.
 *
 * <p>Since tree-sitter parses in an error-tolerant way, modules are extracted even from
 * files that contain syntax errors elsewhere (e.g., historical Python 2 code).</p>
 */
@Slf4j
@ToString
@Command(name = "@historage-ts", description = "Generate finer-grained modules via tree-sitter")
public class HistorageTreeSitter extends HistorageBase {
    public static final NameFilter PYTHON = new NameFilter(true, "*.py");

    public static final NameFilter JAVA = new NameFilter(true, "*.java");

    @Option(names = "--no-classes", negatable = true, description = "[ex]/include class files")
    protected boolean requiresClasses = true;

    @Option(names = "--no-methods", negatable = true, description = "[ex]/include function files")
    protected boolean requiresMethods = true;

    @Option(names = "--no-fields", negatable = true, description = "[ex]/include field files")
    protected boolean requiresFields = true;

    /**
     * Tree-sitter parsers are not thread-safe; one per thread and language.
     */
    private static final ThreadLocal<TSParser> PYTHON_PARSER = ThreadLocal.withInitial(() -> {
        final TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterPython());
        return parser;
    });

    private static final ThreadLocal<TSParser> JAVA_PARSER = ThreadLocal.withInitial(() -> {
        final TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterJava());
        return parser;
    });

    @Override
    protected boolean accepts(final BlobEntry entry) {
        return PYTHON.accept(entry) || JAVA.accept(entry);
    }

    @Override
    protected List<? extends HotEntry> generateModules(final BlobEntry entry, final Context c) {
        final List<Module> modules;
        if (PYTHON.accept(entry)) {
            modules = new PythonModuleGenerator(entry.getName(), PythonSource.decode(entry.getBlob())).generate();
        } else {
            modules = new JavaModuleGenerator(entry.getName(), SourceText.ofNormalized(entry.getBlob())).generate();
        }
        Module.resolveNameConflicts(modules);
        return modules.stream()
                .map(m -> HotEntry.of(entry.getMode(), m.getFilename(), m.getBlob()))
                .toList();
    }

    protected static String baseName(final String filename) {
        final int index = filename.lastIndexOf('.');
        return index > 0 ? filename.substring(0, index) : filename;
    }

    /**
     * Walks the tree-sitter CST of a Python file and generates {@link Module} instances for
     * classes and functions. Descends into class bodies and statement blocks, but not into
     * function bodies.
     */
    public class PythonModuleGenerator {
        private final String filename;

        private final SourceText text;

        private final List<Module> modules = new ArrayList<>();

        private final Module file;

        public PythonModuleGenerator(final String filename, final SourceText text) {
            this.filename = filename;
            this.text = text;
            this.file = Module.ofFile(baseName(filename), PythonNaming.INSTANCE);
        }

        /**
         * Generates a list of Historage modules.
         */
        public List<Module> generate() {
            final TSTree tree = PYTHON_PARSER.get().parseString(null, text.getContent());
            final TSNode root = tree.getRootNode();
            if (root.hasError()) {
                log.debug("Syntax errors found; extracting the modules that parsed");
            }
            walk(root, file, true);
            return modules;
        }

        /**
         * Walks the children of a node. {@code direct} tells whether they are directly at the top
         * level of the file or of a class body, which is where fields are defined.
         */
        protected void walk(final TSNode node, final Module parent, final boolean direct) {
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
        protected void visitClass(final TSNode extent, final TSNode def, final Module parent) {
            final String name = textOf(def.getChildByFieldName("name"));
            final Module klass = new Module(Kind.CLASS, name, parent, contentOf(extent),
                    Kind.CLASS.extension(filename), PythonNaming.INSTANCE);
            if (requiresClasses) {
                modules.add(klass);
            }
            final TSNode body = def.getChildByFieldName("body");
            if (!body.isNull()) {
                walk(body, klass, true);
            }
        }

        /**
         * Visits a function definition; does not descend into its body, so nested
         * functions are kept inside their enclosing function's module.
         */
        protected void visitFunction(final TSNode extent, final TSNode def, final Module parent) {
            if (requiresMethods) {
                final String name = textOf(def.getChildByFieldName("name"));
                final String signature = generateSignature(def.getChildByFieldName("parameters"));
                modules.add(new Module(Kind.METHOD, name + "(" + signature + ")", parent, contentOf(extent),
                        Kind.METHOD.extension(filename), PythonNaming.INSTANCE));
            }
        }

        /**
         * Visits a top-level statement of a file or class body; a plain assignment to a single
         * name (as tree-sitter-python's {@code tags.scm} captures with {@code definition.constant})
         * becomes a field module.
         */
        protected void visitField(final TSNode statement, final Module parent) {
            if (!requiresFields || statement.getNamedChildCount() == 0) {
                return;
            }
            final TSNode assignment = statement.getNamedChild(0);
            if (!assignment.getType().equals("assignment")) {
                return;
            }
            final TSNode left = assignment.getChildByFieldName("left");
            if (!left.isNull() && left.getType().equals("identifier")) {
                modules.add(new Module(Kind.FIELD, textOf(left), parent, contentOf(statement),
                        Kind.FIELD.extension(filename), PythonNaming.INSTANCE));
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
                    names.add(Historage.escape(name));
                }
            }
            return String.join(",", names);
        }

        protected String textOf(final TSNode node) {
            if (node.isNull()) {
                return "";
            }
            return text.getContent().substring(text.toCharIndex(node.getStartByte()), text.toCharIndex(node.getEndByte()));
        }

        /**
         * Extracts the full source lines of the given definition. The extent ends at the last
         * meaningful (non-comment) descendant, since tree-sitter blocks also hold the comments
         * trailing after the last statement.
         */
        protected String contentOf(final TSNode node) {
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

    /**
     * Walks the tree-sitter CST of a Java file and generates {@link Module} instances with the
     * same extraction semantics and FinerGit-compatible naming as {@link HistorageJdt}: type
     * declarations, methods and constructors, and one module per declared field. Method and
     * constructor bodies, field initializers, and anonymous class bodies are not descended into;
     * initializer blocks are, so local classes there are extracted like HistorageJdt does.
     */
    public class JavaModuleGenerator {
        private final String filename;

        private final SourceText text;

        private final List<Module> modules = new ArrayList<>();

        private final Module file;

        public JavaModuleGenerator(final String filename, final SourceText text) {
            this.filename = filename;
            this.text = text;
            this.file = Module.ofFile(baseName(filename), FinerGitNaming.INSTANCE);
        }

        /**
         * Generates a list of Historage modules.
         */
        public List<Module> generate() {
            final TSTree tree = JAVA_PARSER.get().parseString(null, text.getContent());
            final TSNode root = tree.getRootNode();
            if (root.hasError()) {
                log.debug("Syntax errors found; extracting the modules that parsed");
            }
            walk(root, file);
            return modules;
        }

        protected void walk(final TSNode node, final Module parent) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode child = node.getNamedChild(i);
                switch (child.getType()) {
                    case "class_declaration", "interface_declaration", "enum_declaration",
                         "annotation_type_declaration", "record_declaration" -> visitType(child, parent);
                    case "method_declaration", "constructor_declaration", "compact_constructor_declaration"
                            -> visitMethod(child, parent);
                    case "field_declaration", "constant_declaration" -> visitField(child, parent);
                    // a class body reached here belongs to an anonymous class (object creation,
                    // enum constant); skipped like HistorageJdt
                    case "class_body" -> { }
                    default -> {
                        if (!child.isError()) {
                            walk(child, parent);
                        }
                    }
                }
            }
        }

        protected void visitType(final TSNode node, final Module parent) {
            final String name = textOf(node.getChildByFieldName("name"));
            final Module klass = new Module(Kind.CLASS, name, parent, contentOf(node),
                    Kind.CLASS.extension(filename), FinerGitNaming.INSTANCE);
            if (requiresClasses) {
                modules.add(klass);
            }
            final TSNode body = node.getChildByFieldName("body");
            if (!body.isNull()) {
                walk(body, klass);
            }
        }

        protected void visitMethod(final TSNode node, final Module parent) {
            if (requiresMethods) {
                modules.add(new Module(Kind.METHOD, generateMethodName(node), parent, contentOf(node),
                        Kind.METHOD.extension(filename), FinerGitNaming.INSTANCE));
            }
        }

        protected void visitField(final TSNode node, final Module parent) {
            if (!requiresFields) {
                return;
            }
            final String content = contentOf(node);
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode child = node.getNamedChild(i);
                if (child.getType().equals("variable_declarator")) {
                    final String name = textOf(child.getChildByFieldName("name"));
                    modules.add(new Module(Kind.FIELD, name, parent, content,
                            Kind.FIELD.extension(filename), FinerGitNaming.INSTANCE));
                }
            }
        }

        /**
         * Generates a FinerGit-compatible method name: {@code [typeParams]_name(paramTypes)},
         * mirroring {@link HistorageJdt.MethodNameGenerator}.
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
         * annotations, final modifiers, and the parameter names, mirroring how JDT renders the
         * types (annotated varargs lose their annotation there too).
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
         * Prints array dimensions the way JDT does: plain brackets adjacent, an annotated
         * dimension as {@code " @Anno []"}.
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
         * Prints a type the way JDT's AST flattener does (e.g., no spaces inside type arguments),
         * so that the generated names match {@link HistorageJdt}.
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
         * The escaping {@link HistorageJdt.MethodNameGenerator} applies to type names.
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

        protected String textOf(final TSNode node) {
            if (node.isNull()) {
                return "";
            }
            return text.getContent().substring(text.toCharIndex(node.getStartByte()), text.toCharIndex(node.getEndByte()));
        }

        /**
         * Extracts the source of the given declaration with its surrounding comments, like
         * HistorageJdt's fragment extraction: the leading comment run directly above (except
         * comments that trail the previous sibling on its own line) and the trailing comments on
         * the same line as the declaration end.
         */
        protected String contentOf(final TSNode node) {
            final int begin = text.toCharIndex(attachedStart(node));
            final int end = text.toCharIndex(attachedEnd(node));
            return text.getFragment(begin, end).getWiderContent();
        }

        protected boolean isComment(final TSNode node) {
            return node.getType().equals("line_comment") || node.getType().equals("block_comment");
        }

        /**
         * The start of the leading comment run, following JDT. A doc comment directly preceding
         * the declaration is bound to it regardless of blank lines, like the Eclipse parser binds
         * Javadoc. Above that, comments chain upward as long as no blank line intervenes, as in
         * JDT's {@code DefaultCommentMapper}; a comment starting on the same line as the previous
         * sibling's end (for the first sibling: the file start) trails that sibling instead and
         * stops the chain.
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
         * The end of the trailing comment run, following JDT's {@code DefaultCommentMapper}:
         * comments chain downward until a blank line; unless the declaration is the last member,
         * the run must be separated from the next declaration by a blank line, or only the
         * comments on the declaration's own end line trail it.
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
}
