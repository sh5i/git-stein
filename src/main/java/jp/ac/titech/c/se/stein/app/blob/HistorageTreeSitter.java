package jp.ac.titech.c.se.stein.app.blob;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.historage.FinerGitNaming;
import jp.ac.titech.c.se.stein.historage.Kind;
import jp.ac.titech.c.se.stein.historage.Module;
import jp.ac.titech.c.se.stein.historage.NamingStrategy;
import jp.ac.titech.c.se.stein.historage.ScopedNaming;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import jp.ac.titech.c.se.stein.util.PythonSource;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterC;
import org.treesitter.TreeSitterCSharp;
import org.treesitter.TreeSitterCpp;
import org.treesitter.TreeSitterGo;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterKotlin;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterRuby;
import org.treesitter.TreeSitterRust;
import org.treesitter.TreeSitterSwift;
import org.treesitter.TreeSitterTypescript;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * A Historage generator using tree-sitter, currently supporting Python, Java, C, C++, C#,
 * JavaScript, TypeScript, Go, Kotlin, Rust, Swift, and Ruby.
 * Splits source files into finer-grained modules (one file per class, function, or field).
 * Java files follow the same extraction semantics and FinerGit-compatible naming as
 * {@link HistorageJdt}; the other languages use an analogous scoped naming of their own.
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

    public static final NameFilter C = new NameFilter(true, "*.c");

    public static final NameFilter CPP = new NameFilter(true,
            "*.cpp", "*.cc", "*.cxx", "*.hpp", "*.hh", "*.hxx", "*.h");

    public static final NameFilter GO = new NameFilter(true, "*.go");

    public static final NameFilter KOTLIN = new NameFilter(true, "*.kt", "*.kts");

    public static final NameFilter RUST = new NameFilter(true, "*.rs");

    public static final NameFilter SWIFT = new NameFilter(true, "*.swift");

    public static final NameFilter RUBY = new NameFilter(true, "*.rb");

    public static final NameFilter CSHARP = new NameFilter(true, "*.cs");

    public static final NameFilter JAVASCRIPT = new NameFilter(true, "*.js", "*.mjs", "*.cjs", "*.jsx");

    public static final NameFilter TYPESCRIPT = new NameFilter(true, "*.ts", "*.mts", "*.cts");

    @Option(names = "--no-classes", negatable = true, description = "[ex]/include class files")
    protected boolean requiresClasses = true;

    @Option(names = "--no-methods", negatable = true, description = "[ex]/include function files")
    protected boolean requiresMethods = true;

    @Option(names = "--no-fields", negatable = true, description = "[ex]/include field files")
    protected boolean requiresFields = true;

    /**
     * The registered languages, tried in order; the first whose filter accepts a blob handles it.
     * Adding a language is a matter of registering one more profile here.
     */
    private final List<LanguageProfile> profiles = List.of(
            new LanguageProfile(PYTHON, TreeSitterPython::new, PythonSource::decode, PythonModuleGenerator::new),
            new LanguageProfile(JAVA, TreeSitterJava::new, SourceText::ofNormalized,
                    (fn, txt) -> new JavaModuleGenerator(fn, txt, requiresClasses, requiresMethods, requiresFields)),
            new LanguageProfile(CPP, TreeSitterCpp::new, SourceText::ofNormalized, CppModuleGenerator::new),
            new LanguageProfile(CSHARP, TreeSitterCSharp::new, SourceText::ofNormalized, CSharpModuleGenerator::new),
            new LanguageProfile(JAVASCRIPT, TreeSitterJavascript::new, SourceText::ofNormalized, JsModuleGenerator::new),
            new LanguageProfile(TYPESCRIPT, TreeSitterTypescript::new, SourceText::ofNormalized, TsModuleGenerator::new),
            // C is a subset of C++, so it reuses the C++ generator with the C grammar
            new LanguageProfile(C, TreeSitterC::new, SourceText::ofNormalized, CppModuleGenerator::new),
            new LanguageProfile(GO, TreeSitterGo::new, SourceText::ofNormalized, GoModuleGenerator::new),
            new LanguageProfile(KOTLIN, TreeSitterKotlin::new, SourceText::ofNormalized, KotlinModuleGenerator::new),
            new LanguageProfile(RUST, TreeSitterRust::new, SourceText::ofNormalized, RustModuleGenerator::new),
            new LanguageProfile(SWIFT, TreeSitterSwift::new, SourceText::ofNormalized, SwiftModuleGenerator::new),
            new LanguageProfile(RUBY, TreeSitterRuby::new, SourceText::ofNormalized, RubyModuleGenerator::new));

    @Override
    protected boolean accepts(final BlobEntry entry) {
        return profiles.stream().anyMatch(p -> p.accepts(entry));
    }

    @Override
    protected List<? extends HotEntry> generateModules(final BlobEntry entry, final Context c) {
        final LanguageProfile profile = profiles.stream()
                .filter(p -> p.accepts(entry))
                .findFirst()
                .orElseThrow();
        final List<Module> modules = profile.generate(entry.getName(), entry.getBlob());
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
     * The per-language wiring for the tree-sitter generator: which files it handles, how to decode
     * and parse them, and how to build the language-specific {@link ModuleGenerator}.
     */
    private class LanguageProfile {
        private final NameFilter filter;

        private final ThreadLocal<TSParser> parser;

        private final Function<byte[], SourceText> decoder;

        private final BiFunction<String, SourceText, ModuleGenerator> generator;

        LanguageProfile(final NameFilter filter, final Supplier<TSLanguage> language,
                        final Function<byte[], SourceText> decoder,
                        final BiFunction<String, SourceText, ModuleGenerator> generator) {
            this.filter = filter;
            // tree-sitter parsers are not thread-safe; one per thread and language
            this.parser = ThreadLocal.withInitial(() -> {
                final TSParser p = new TSParser();
                p.setLanguage(language.get());
                return p;
            });
            this.decoder = decoder;
            this.generator = generator;
        }

        boolean accepts(final BlobEntry entry) {
            return filter.accept(entry);
        }

        List<Module> generate(final String filename, final byte[] blob) {
            final SourceText text = decoder.apply(blob);
            final TSNode root = parser.get().parseString(null, text.getContent()).getRootNode();
            if (root.hasError()) {
                log.debug("Syntax errors found; extracting the modules that parsed");
            }
            return generator.apply(filename, text).run(root);
        }
    }

    /**
     * The shared skeleton of a tree-sitter module generator. A subclass walks its language's CST in
     * {@link #run} and emits {@link Module} instances via {@link #module}.
     */
    public abstract static class ModuleGenerator {
        protected final String filename;

        protected final SourceText text;

        protected final NamingStrategy naming;

        protected final List<Module> modules = new ArrayList<>();

        protected final Module file;

        protected ModuleGenerator(final String filename, final SourceText text, final NamingStrategy naming) {
            this.filename = filename;
            this.text = text;
            this.naming = naming;
            this.file = Module.ofFile(baseName(filename), naming);
        }

        /**
         * Walks the parsed tree from its root and returns the extracted modules.
         */
        public abstract List<Module> run(TSNode root);

        protected Module module(final Kind kind, final String name, final Module parent, final String content) {
            return new Module(kind, name, parent, content, kind.extension(filename), naming);
        }

        protected String textOf(final TSNode node) {
            if (node.isNull()) {
                return "";
            }
            return text.getContent().substring(text.toCharIndex(node.getStartByte()), text.toCharIndex(node.getEndByte()));
        }

        /**
         * Flattens a possibly qualified name into a file-system-safe leaf, turning the {@code ::}
         * scope operator into {@code .} and escaping the remaining reserved characters.
         */
        protected String flatten(final String name) {
            return Historage.escape(name.replace("::", "."));
        }

        /**
         * The first named child of the given type, or a null node if there is none.
         */
        protected TSNode firstChildOfType(final TSNode node, final String type) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode child = node.getNamedChild(i);
                if (child.getType().equals(type)) {
                    return child;
                }
            }
            return null;
        }

        /**
         * The full source lines spanning the given node.
         */
        protected String contentOf(final TSNode node) {
            final int beginLine = node.getStartPoint().getRow() + 1;
            final int endLine = node.getEndPoint().getRow() + 1;
            return text.getFragmentOfLines(beginLine, endLine).getWiderContent();
        }
    }

    /**
     * Walks the tree-sitter CST of a Python file and generates {@link Module} instances for
     * classes and functions. Descends into class bodies and statement blocks, but not into
     * function bodies.
     */
    public class PythonModuleGenerator extends ModuleGenerator {
        public PythonModuleGenerator(final String filename, final SourceText text) {
            super(filename, text, ScopedNaming.INSTANCE);
        }

        @Override
        public List<Module> run(final TSNode root) {
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
            final Module klass = module(Kind.CLASS, name, parent, contentOf(extent));
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
                modules.add(module(Kind.METHOD, name + "(" + signature + ")", parent, contentOf(extent)));
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
                modules.add(module(Kind.FIELD, textOf(left), parent, contentOf(statement)));
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
    public static class JavaModuleGenerator extends ModuleGenerator {
        protected final boolean requiresClasses;

        protected final boolean requiresMethods;

        protected final boolean requiresFields;

        public JavaModuleGenerator(final String filename, final SourceText text, final boolean requiresClasses,
                                   final boolean requiresMethods, final boolean requiresFields) {
            super(filename, text, FinerGitNaming.INSTANCE);
            this.requiresClasses = requiresClasses;
            this.requiresMethods = requiresMethods;
            this.requiresFields = requiresFields;
        }

        @Override
        public List<Module> run(final TSNode root) {
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
            // a subclass that does not emit classes (e.g. Finer) uses the class only as a naming scope
            final Module klass = module(Kind.CLASS, name, parent, requiresClasses ? contentOf(node) : null);
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
                modules.add(module(Kind.METHOD, generateMethodName(node), parent, contentOf(node)));
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
                    modules.add(module(Kind.FIELD, name, parent, content));
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

    /**
     * Walks the tree-sitter CST of a C++ file and generates {@link Module} instances for classes
     * (class/struct/union/enum), functions with a body (free, member, and out-of-line
     * {@code Class::method} definitions), and data members and namespace/file-scope variables.
     * Namespaces are naming scopes only; function bodies are not descended into. Names use the
     * {@code ::} scope operator flattened to {@code .} to stay portable across file systems.
     */
    public class CppModuleGenerator extends ModuleGenerator {
        public CppModuleGenerator(final String filename, final SourceText text) {
            super(filename, text, ScopedNaming.INSTANCE);
        }

        @Override
        public List<Module> run(final TSNode root) {
            walk(root, file);
            return modules;
        }

        protected void walk(final TSNode node, final Module parent) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode child = node.getNamedChild(i);
                switch (child.getType()) {
                    case "class_specifier", "struct_specifier", "union_specifier" -> visitType(child, child, parent);
                    case "enum_specifier" -> visitEnum(child, child, parent);
                    case "function_definition" -> visitFunction(child, child, parent);
                    case "template_declaration" -> visitTemplate(child, parent);
                    case "field_declaration" -> visitField(child, parent);
                    case "declaration" -> visitDeclaration(child, parent);
                    case "namespace_definition" -> visitNamespace(child, parent);
                    // descend into linkage_specification (extern "C"), preprocessor blocks, etc.
                    default -> {
                        if (!child.isError()) {
                            walk(child, parent);
                        }
                    }
                }
            }
        }

        /**
         * Visits a namespace: a naming scope we descend into but never emit as a module (it would
         * span whole files). An anonymous namespace is transparent.
         */
        protected void visitNamespace(final TSNode node, final Module parent) {
            final TSNode name = node.getChildByFieldName("name");
            final Module scope = name.isNull() ? parent : module(Kind.CLASS, flatten(textOf(name)), parent, null);
            final TSNode body = node.getChildByFieldName("body");
            if (!body.isNull()) {
                walk(body, scope);
            }
        }

        /**
         * Visits a class/struct/union. {@code extent} covers the whole extracted range (including a
         * template header); {@code def} is the specifier itself. An anonymous type is transparent.
         */
        protected void visitType(final TSNode extent, final TSNode def, final Module parent) {
            final TSNode name = def.getChildByFieldName("name");
            final TSNode body = def.getChildByFieldName("body");
            if (name.isNull()) {
                if (!body.isNull()) {
                    walk(body, parent);
                }
                return;
            }
            final Module klass = module(Kind.CLASS, flatten(textOf(name)), parent, contentOf(extent));
            if (requiresClasses) {
                modules.add(klass);
            }
            if (!body.isNull()) {
                walk(body, klass);
            }
        }

        protected void visitEnum(final TSNode extent, final TSNode def, final Module parent) {
            final TSNode name = def.getChildByFieldName("name");
            if (requiresClasses && !name.isNull()) {
                modules.add(module(Kind.CLASS, flatten(textOf(name)), parent, contentOf(extent)));
            }
        }

        /**
         * Unwraps a template declaration, visiting the class or function it wraps with the template
         * header included in the extent.
         */
        protected void visitTemplate(final TSNode node, final Module parent) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode child = node.getNamedChild(i);
                switch (child.getType()) {
                    case "class_specifier", "struct_specifier", "union_specifier" -> {
                        visitType(node, child, parent);
                        return;
                    }
                    case "enum_specifier" -> {
                        visitEnum(node, child, parent);
                        return;
                    }
                    case "function_definition" -> {
                        visitFunction(node, child, parent);
                        return;
                    }
                    default -> { }
                }
            }
        }

        protected void visitFunction(final TSNode extent, final TSNode def, final Module parent) {
            if (!requiresMethods) {
                return;
            }
            final TSNode fd = functionDeclarator(def.getChildByFieldName("declarator"));
            if (fd == null) {
                return;
            }
            final TSNode name = fd.getChildByFieldName("declarator");
            if (name.isNull()) {
                return;
            }
            final String signature = signature(fd.getChildByFieldName("parameters"));
            modules.add(module(Kind.METHOD, flatten(textOf(name)) + "(" + signature + ")", parent, contentOf(extent)));
        }

        /**
         * Visits a class-body field declaration: one field module per declared data member. A
         * member function prototype (no body) is skipped, since the implemented definition is what
         * carries the history.
         */
        protected void visitField(final TSNode node, final Module parent) {
            if (!requiresFields || functionDeclarator(node.getChildByFieldName("declarator")) != null) {
                return;
            }
            final String content = contentOf(node);
            for (final TSNode name : fieldNames(node)) {
                modules.add(module(Kind.FIELD, flatten(textOf(name)), parent, content));
            }
        }

        /**
         * Visits a namespace/file-scope declaration: one field module per declared variable.
         * Function prototypes, typedefs, and using-declarations have no init-declarator and are
         * skipped.
         */
        protected void visitDeclaration(final TSNode node, final Module parent) {
            if (!requiresFields || functionDeclarator(node.getChildByFieldName("declarator")) != null) {
                return;
            }
            final String content = contentOf(node);
            for (int i = 0; i < node.getChildCount(); i++) {
                if (!"declarator".equals(node.getFieldNameForChild(i))) {
                    continue;
                }
                final TSNode d = node.getChild(i);
                final TSNode inner = d.getType().equals("init_declarator") ? d.getChildByFieldName("declarator") : d;
                final TSNode name = declaratorName(inner);
                if (name != null) {
                    modules.add(module(Kind.FIELD, flatten(textOf(name)), parent, content));
                }
            }
        }

        /**
         * Unwraps pointer and reference declarators (e.g. the {@code *} of a pointer return type)
         * to find the function declarator, or null if there is none.
         */
        protected TSNode functionDeclarator(final TSNode node) {
            if (node == null || node.isNull()) {
                return null;
            }
            return node.getType().equals("function_declarator") ? node
                    : functionDeclarator(node.getChildByFieldName("declarator"));
        }

        /**
         * Finds the innermost name of a declarator, descending through pointer, reference, and array
         * declarators.
         */
        protected TSNode declaratorName(final TSNode node) {
            if (node == null || node.isNull()) {
                return null;
            }
            switch (node.getType()) {
                case "identifier", "field_identifier", "qualified_identifier" -> {
                    return node;
                }
                default -> {
                    final TSNode d = node.getChildByFieldName("declarator");
                    if (d != null && !d.isNull()) {
                        return declaratorName(d);
                    }
                    // some declarators (e.g. a reference declarator) hold their inner name unnamed
                    for (int i = 0; i < node.getNamedChildCount(); i++) {
                        final TSNode r = declaratorName(node.getNamedChild(i));
                        if (r != null) {
                            return r;
                        }
                    }
                    return null;
                }
            }
        }

        /**
         * Collects the {@code field_identifier} names declared by a data-member field declaration,
         * descending through pointer and array declarators to catch each declared name.
         */
        protected List<TSNode> fieldNames(final TSNode node) {
            final List<TSNode> result = new ArrayList<>();
            collectFieldNames(node, result);
            return result;
        }

        protected void collectFieldNames(final TSNode node, final List<TSNode> out) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode child = node.getNamedChild(i);
                if (child.getType().equals("field_identifier")) {
                    out.add(child);
                } else if (child.getType().endsWith("declarator")) {
                    collectFieldNames(child, out);
                }
            }
        }

        /**
         * Renders a parameter as its type, dropping the parameter name and any default value.
         */
        protected String signature(final TSNode parameters) {
            if (parameters.isNull()) {
                return "";
            }
            final List<String> types = new ArrayList<>();
            for (int i = 0; i < parameters.getNamedChildCount(); i++) {
                final TSNode p = parameters.getNamedChild(i);
                switch (p.getType()) {
                    case "parameter_declaration", "optional_parameter_declaration" -> types.add(parameterType(p));
                    case "variadic_parameter_declaration" -> types.add("...");
                    default -> {
                        if (textOf(p).equals("...")) {
                            types.add("...");
                        }
                    }
                }
            }
            return String.join(",", types);
        }

        protected String parameterType(final TSNode p) {
            final TSNode name = declaratorName(p.getChildByFieldName("declarator"));
            final String content = text.getContent();
            String type;
            if (name != null && !name.isNull()) {
                type = content.substring(text.toCharIndex(p.getStartByte()), text.toCharIndex(name.getStartByte()))
                        + content.substring(text.toCharIndex(name.getEndByte()), text.toCharIndex(p.getEndByte()));
            } else {
                type = textOf(p);
            }
            final int eq = type.indexOf('=');
            return Historage.escape(eq >= 0 ? type.substring(0, eq) : type);
        }

        protected String contentOf(final TSNode node) {
            final int beginLine = node.getStartPoint().getRow() + 1;
            final int endLine = node.getEndPoint().getRow() + 1;
            return text.getFragmentOfLines(beginLine, endLine).getWiderContent();
        }
    }

    /**
     * Walks the tree-sitter CST of a C# file and generates {@link Module} instances for types
     * (class/struct/interface/record/enum), methods (methods, constructors, destructors, and
     * operators), and data members (fields, properties, and events). Namespaces are naming scopes
     * only; method bodies are not descended into. Property and event declarations are treated as
     * fields. Names use the scoped naming, escaped to stay portable across file systems.
     */
    public class CSharpModuleGenerator extends ModuleGenerator {
        public CSharpModuleGenerator(final String filename, final SourceText text) {
            super(filename, text, ScopedNaming.INSTANCE);
        }

        @Override
        public List<Module> run(final TSNode root) {
            walk(root, file);
            return modules;
        }

        protected void walk(final TSNode node, Module parent) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode child = node.getNamedChild(i);
                switch (child.getType()) {
                    case "class_declaration", "struct_declaration", "interface_declaration",
                         "record_declaration", "record_struct_declaration" -> visitType(child, parent);
                    case "enum_declaration" -> visitEnum(child, parent);
                    case "method_declaration", "constructor_declaration", "destructor_declaration",
                         "operator_declaration" -> visitMethod(child, parent);
                    case "field_declaration", "event_field_declaration" -> visitField(child, parent);
                    case "property_declaration" -> visitProperty(child, parent);
                    case "namespace_declaration" -> visitNamespace(child, parent);
                    // a file-scoped namespace scopes every sibling that follows it
                    case "file_scoped_namespace_declaration" -> parent = scopeOf(child, parent);
                    default -> {
                        if (!child.isError()) {
                            walk(child, parent);
                        }
                    }
                }
            }
        }

        protected void visitNamespace(final TSNode node, final Module parent) {
            final Module scope = scopeOf(node, parent);
            final TSNode body = node.getChildByFieldName("body");
            if (!body.isNull()) {
                walk(body, scope);
            }
        }

        /**
         * The naming scope of a namespace: never emitted as a module (it would span whole files).
         * An anonymous namespace is transparent.
         */
        protected Module scopeOf(final TSNode node, final Module parent) {
            final TSNode name = node.getChildByFieldName("name");
            return name.isNull() ? parent : module(Kind.CLASS, flatten(textOf(name)), parent, null);
        }

        protected void visitType(final TSNode node, final Module parent) {
            final TSNode name = node.getChildByFieldName("name");
            if (name.isNull()) {
                return;
            }
            final Module klass = module(Kind.CLASS, flatten(textOf(name)), parent, contentOf(node));
            if (requiresClasses) {
                modules.add(klass);
            }
            final TSNode body = node.getChildByFieldName("body");
            if (!body.isNull()) {
                walk(body, klass);
            }
        }

        protected void visitEnum(final TSNode node, final Module parent) {
            final TSNode name = node.getChildByFieldName("name");
            if (requiresClasses && !name.isNull()) {
                modules.add(module(Kind.CLASS, flatten(textOf(name)), parent, contentOf(node)));
            }
        }

        protected void visitMethod(final TSNode node, final Module parent) {
            if (requiresMethods) {
                modules.add(module(Kind.METHOD, methodName(node), parent, contentOf(node)));
            }
        }

        /**
         * A field declaration (each variable declarator), a property, or an event becomes one field
         * module; properties and events are named members without a parameter signature.
         */
        protected void visitField(final TSNode node, final Module parent) {
            if (!requiresFields) {
                return;
            }
            final String content = contentOf(node);
            final TSNode declaration = childOfType(node, "variable_declaration");
            if (declaration == null) {
                return;
            }
            for (int i = 0; i < declaration.getNamedChildCount(); i++) {
                final TSNode declarator = declaration.getNamedChild(i);
                if (declarator.getType().equals("variable_declarator")) {
                    final TSNode name = declarator.getChildByFieldName("name");
                    if (!name.isNull()) {
                        modules.add(module(Kind.FIELD, flatten(textOf(name)), parent, content));
                    }
                }
            }
        }

        protected void visitProperty(final TSNode node, final Module parent) {
            final TSNode name = node.getChildByFieldName("name");
            if (requiresFields && !name.isNull()) {
                modules.add(module(Kind.FIELD, flatten(textOf(name)), parent, contentOf(node)));
            }
        }

        /**
         * Generates a FinerGit-style method name {@code [typeParams]_name(paramTypes)}, prefixing a
         * destructor with {@code ~} and naming an operator {@code operator<symbol>}.
         */
        protected String methodName(final TSNode node) {
            final StringBuilder sb = new StringBuilder();
            final TSNode typeParameters = node.getChildByFieldName("type_parameters");
            if (typeParameters != null && !typeParameters.isNull()) {
                sb.append("[").append(typeParameters(typeParameters)).append("]_");
            }
            switch (node.getType()) {
                case "destructor_declaration" -> sb.append("~").append(textOf(node.getChildByFieldName("name")));
                case "operator_declaration" -> sb.append("operator").append(textOf(node.getChildByFieldName("operator")));
                default -> sb.append(textOf(node.getChildByFieldName("name")));
            }
            sb.append("(").append(signature(node.getChildByFieldName("parameters"))).append(")");
            return flatten(sb.toString());
        }

        protected String typeParameters(final TSNode list) {
            final List<String> names = new ArrayList<>();
            for (int i = 0; i < list.getNamedChildCount(); i++) {
                final TSNode child = list.getNamedChild(i);
                if (child.getType().equals("type_parameter")) {
                    names.add(textOf(child.getChildByFieldName("name")));
                }
            }
            return String.join(",", names);
        }

        /**
         * Renders each parameter as its declared type, dropping the parameter name; the enclosing
         * {@link #methodName} flattens any generic angle brackets into a portable form.
         */
        protected String signature(final TSNode parameters) {
            if (parameters.isNull()) {
                return "";
            }
            final List<String> types = new ArrayList<>();
            for (int i = 0; i < parameters.getNamedChildCount(); i++) {
                final TSNode p = parameters.getNamedChild(i);
                if (p.getType().equals("parameter")) {
                    final TSNode type = p.getChildByFieldName("type");
                    if (!type.isNull()) {
                        types.add(textOf(type).replaceAll("\\s+", ""));
                    }
                }
            }
            return String.join(",", types);
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

        protected String contentOf(final TSNode node) {
            final int beginLine = node.getStartPoint().getRow() + 1;
            final int endLine = node.getEndPoint().getRow() + 1;
            return text.getFragmentOfLines(beginLine, endLine).getWiderContent();
        }
    }

    /**
     * Walks the tree-sitter CST of a JavaScript file and generates {@link Module} instances for
     * classes, functions (function declarations, class methods, and arrow or function expressions
     * bound to a variable), and fields (class fields and non-function top-level bindings). Function
     * bodies are not descended into, and object-literal methods are not extracted. Since JavaScript
     * is untyped, a signature lists parameter names.
     */
    public class JsModuleGenerator extends ModuleGenerator {
        public JsModuleGenerator(final String filename, final SourceText text) {
            super(filename, text, ScopedNaming.INSTANCE);
        }

        @Override
        public List<Module> run(final TSNode root) {
            walk(root, file);
            return modules;
        }

        protected void walk(final TSNode node, final Module parent) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode child = node.getNamedChild(i);
                if (child.getType().equals("export_statement")) {
                    visitExport(child, parent);
                } else if (!dispatch(child, child, parent) && !child.isError()) {
                    walk(child, parent);
                }
            }
        }

        /**
         * Dispatches a declaration to its visitor, using {@code extent} as the content range (which
         * differs from {@code def} when unwrapping an export). Returns whether it was handled;
         * subclasses override to add language constructs and delegate the rest to {@code super}.
         */
        protected boolean dispatch(final TSNode extent, final TSNode def, final Module parent) {
            switch (def.getType()) {
                case "class_declaration" -> visitClass(extent, def, parent);
                case "function_declaration", "generator_function_declaration" -> visitMethod(extent, def, parent);
                case "lexical_declaration", "variable_declaration" -> visitDeclaration(extent, def, parent);
                default -> {
                    return false;
                }
            }
            return true;
        }

        /**
         * Unwraps an export statement, visiting the declaration it exports with the {@code export}
         * keyword included in the extent. An anonymous default export or a re-export has nothing
         * named to extract.
         */
        protected void visitExport(final TSNode node, final Module parent) {
            final TSNode decl = node.getChildByFieldName("declaration");
            if (!decl.isNull()) {
                dispatch(node, decl, parent);
            }
        }

        protected void visitClass(final TSNode extent, final TSNode def, final Module parent) {
            final TSNode name = def.getChildByFieldName("name");
            if (name.isNull()) {
                return;
            }
            final Module klass = module(Kind.CLASS, flatten(textOf(name)), parent, contentOf(extent));
            if (requiresClasses) {
                modules.add(klass);
            }
            final TSNode body = def.getChildByFieldName("body");
            if (body.isNull()) {
                return;
            }
            for (int i = 0; i < body.getNamedChildCount(); i++) {
                final TSNode member = body.getNamedChild(i);
                switch (member.getType()) {
                    case "method_definition" -> visitMethod(member, member, klass);
                    case "field_definition", "public_field_definition" -> visitField(member, klass);
                    default -> { }
                }
            }
        }

        protected void visitMethod(final TSNode extent, final TSNode def, final Module parent) {
            if (!requiresMethods) {
                return;
            }
            final TSNode name = def.getChildByFieldName("name");
            if (!name.isNull()) {
                modules.add(module(Kind.METHOD, flatten(textOf(name)) + "(" + signature(def) + ")", parent, contentOf(extent)));
            }
        }

        protected void visitField(final TSNode node, final Module parent) {
            if (!requiresFields) {
                return;
            }
            // a JavaScript field_definition names it "property"; a TypeScript field/signature "name"
            TSNode name = node.getChildByFieldName("property");
            if (name.isNull()) {
                name = node.getChildByFieldName("name");
            }
            if (!name.isNull()) {
                modules.add(module(Kind.FIELD, flatten(textOf(name)), parent, contentOf(node)));
            }
        }

        /**
         * A variable binding whose value is a function becomes a method module; any other binding
         * becomes a field module. Destructuring bindings (whose name is a pattern) are skipped.
         */
        protected void visitDeclaration(final TSNode extent, final TSNode def, final Module parent) {
            final String content = contentOf(extent);
            for (int i = 0; i < def.getNamedChildCount(); i++) {
                final TSNode declarator = def.getNamedChild(i);
                if (!declarator.getType().equals("variable_declarator")) {
                    continue;
                }
                final TSNode name = declarator.getChildByFieldName("name");
                if (name.isNull() || !name.getType().equals("identifier")) {
                    continue;
                }
                final TSNode value = declarator.getChildByFieldName("value");
                if (!value.isNull() && isFunction(value)) {
                    if (requiresMethods) {
                        modules.add(module(Kind.METHOD, flatten(textOf(name)) + "(" + signature(value) + ")", parent, content));
                    }
                } else if (requiresFields) {
                    modules.add(module(Kind.FIELD, flatten(textOf(name)), parent, content));
                }
            }
        }

        protected boolean isFunction(final TSNode value) {
            return switch (value.getType()) {
                case "arrow_function", "function_expression", "generator_function" -> true;
                default -> false;
            };
        }

        /**
         * Renders a function's parameter names, dropping default values; a single unparenthesized
         * arrow parameter is held under a {@code parameter} field instead of {@code parameters}.
         */
        protected String signature(final TSNode fn) {
            final TSNode parameters = fn.getChildByFieldName("parameters");
            if (!parameters.isNull()) {
                final List<String> names = new ArrayList<>();
                for (int i = 0; i < parameters.getNamedChildCount(); i++) {
                    names.add(paramName(parameters.getNamedChild(i)));
                }
                return String.join(",", names);
            }
            final TSNode single = fn.getChildByFieldName("parameter");
            return single.isNull() ? "" : paramName(single);
        }

        protected String paramName(final TSNode p) {
            if (p.getType().equals("assignment_pattern")) {
                return paramName(p.getChildByFieldName("left"));
            }
            return Historage.escape(textOf(p).replaceAll("\\s+", ""));
        }

        protected String contentOf(final TSNode node) {
            final int beginLine = node.getStartPoint().getRow() + 1;
            final int endLine = node.getEndPoint().getRow() + 1;
            return text.getFragmentOfLines(beginLine, endLine).getWiderContent();
        }
    }

    /**
     * Walks the tree-sitter CST of a TypeScript file. TypeScript is a superset of JavaScript, so
     * this reuses {@link JsModuleGenerator} and adds the TypeScript-only constructs: namespaces
     * (naming scopes), interfaces and enums (classes), abstract classes, type aliases (fields), and
     * the {@code required}/{@code optional} parameter wrappers. Parameter types are dropped, leaving
     * parameter names in the signature.
     */
    public class TsModuleGenerator extends JsModuleGenerator {
        public TsModuleGenerator(final String filename, final SourceText text) {
            super(filename, text);
        }

        @Override
        protected boolean dispatch(final TSNode extent, final TSNode def, final Module parent) {
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
         * An interface is a class module whose members are its property signatures (fields) and
         * method signatures (methods).
         */
        protected void visitInterface(final TSNode extent, final TSNode def, final Module parent) {
            final TSNode name = def.getChildByFieldName("name");
            if (name.isNull()) {
                return;
            }
            final Module iface = module(Kind.CLASS, flatten(textOf(name)), parent, contentOf(extent));
            if (requiresClasses) {
                modules.add(iface);
            }
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
         * An enum or other named type declaration becomes a class module without descending.
         */
        protected void visitType(final TSNode extent, final TSNode def, final Module parent) {
            final TSNode name = def.getChildByFieldName("name");
            if (requiresClasses && !name.isNull()) {
                modules.add(module(Kind.CLASS, flatten(textOf(name)), parent, contentOf(extent)));
            }
        }

        protected void visitTypeAlias(final TSNode extent, final TSNode def, final Module parent) {
            final TSNode name = def.getChildByFieldName("name");
            if (requiresFields && !name.isNull()) {
                modules.add(module(Kind.FIELD, flatten(textOf(name)), parent, contentOf(extent)));
            }
        }

        /**
         * A namespace ({@code internal_module}) is a naming scope only, never emitted as a module.
         */
        protected void visitNamespace(final TSNode node, final Module parent) {
            final TSNode name = node.getChildByFieldName("name");
            final Module scope = name.isNull() ? parent : module(Kind.CLASS, flatten(textOf(name)), parent, null);
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

    /**
     * Walks the tree-sitter CST of a Go file: types (structs and interfaces become classes, with
     * their fields and interface methods split out; other type specs are fields), free functions,
     * methods (named {@code Receiver.name}), and package-level constants and variables.
     */
    public class GoModuleGenerator extends ModuleGenerator {
        public GoModuleGenerator(final String filename, final SourceText text) {
            super(filename, text, ScopedNaming.INSTANCE);
        }

        @Override
        public List<Module> run(final TSNode root) {
            walk(root, file);
            return modules;
        }

        protected void walk(final TSNode node, final Module parent) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode child = node.getNamedChild(i);
                switch (child.getType()) {
                    case "type_declaration" -> visitTypeDeclaration(child, parent);
                    case "function_declaration" -> visitFunction(child, parent);
                    case "method_declaration" -> visitMethod(child, parent);
                    case "const_declaration", "var_declaration" -> visitVariables(child, parent);
                    default -> {
                        if (!child.isError()) {
                            walk(child, parent);
                        }
                    }
                }
            }
        }

        protected void visitTypeDeclaration(final TSNode node, final Module parent) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode spec = node.getNamedChild(i);
                if (!spec.getType().equals("type_spec")) {
                    continue;
                }
                final TSNode name = spec.getChildByFieldName("name");
                final TSNode type = spec.getChildByFieldName("type");
                if (name.isNull()) {
                    continue;
                }
                if (type.getType().equals("struct_type") || type.getType().equals("interface_type")) {
                    final Module klass = module(Kind.CLASS, flatten(textOf(name)), parent, contentOf(node));
                    if (requiresClasses) {
                        modules.add(klass);
                    }
                    visitTypeBody(type, klass);
                } else if (requiresFields) {
                    modules.add(module(Kind.FIELD, flatten(textOf(name)), parent, contentOf(node)));
                }
            }
        }

        protected void visitTypeBody(final TSNode type, final Module klass) {
            final TSNode body = type.getType().equals("struct_type")
                    ? firstChildOfType(type, "field_declaration_list") : firstChildOfType(type, "interface_type_body");
            final TSNode list = body != null ? body : type;
            for (int i = 0; i < list.getNamedChildCount(); i++) {
                final TSNode member = list.getNamedChild(i);
                if (member.getType().equals("field_declaration")) {
                    for (int j = 0; j < member.getNamedChildCount(); j++) {
                        final TSNode f = member.getNamedChild(j);
                        if (f.getType().equals("field_identifier") && requiresFields) {
                            modules.add(module(Kind.FIELD, flatten(textOf(f)), klass, contentOf(member)));
                        }
                    }
                } else if (member.getType().equals("method_elem") || member.getType().equals("method_spec")) {
                    if (requiresMethods) {
                        final TSNode mn = member.getChildByFieldName("name");
                        modules.add(module(Kind.METHOD, flatten(textOf(mn)) + "(" + signature(member.getChildByFieldName("parameters")) + ")", klass, contentOf(member)));
                    }
                }
            }
        }

        protected void visitFunction(final TSNode node, final Module parent) {
            if (requiresMethods) {
                final TSNode name = node.getChildByFieldName("name");
                modules.add(module(Kind.METHOD, flatten(textOf(name)) + "(" + signature(node.getChildByFieldName("parameters")) + ")", parent, contentOf(node)));
            }
        }

        protected void visitMethod(final TSNode node, final Module parent) {
            if (!requiresMethods) {
                return;
            }
            final TSNode name = node.getChildByFieldName("name");
            final String receiver = receiverType(node.getChildByFieldName("receiver"));
            final String leaf = (receiver.isEmpty() ? "" : receiver + ".") + textOf(name);
            modules.add(module(Kind.METHOD, flatten(leaf) + "(" + signature(node.getChildByFieldName("parameters")) + ")", parent, contentOf(node)));
        }

        /**
         * The receiver type of a method, without a leading pointer star, e.g. {@code Point} for
         * {@code (p *Point)}.
         */
        protected String receiverType(final TSNode receiver) {
            if (receiver.isNull()) {
                return "";
            }
            final TSNode decl = firstChildOfType(receiver, "parameter_declaration");
            if (decl == null) {
                return "";
            }
            return textOf(decl.getChildByFieldName("type")).replaceFirst("^\\*", "");
        }

        protected void visitVariables(final TSNode node, final Module parent) {
            if (!requiresFields) {
                return;
            }
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode spec = node.getNamedChild(i);
                if (!spec.getType().endsWith("_spec")) {
                    continue;
                }
                for (int j = 0; j < spec.getNamedChildCount(); j++) {
                    final TSNode c = spec.getNamedChild(j);
                    if (c.getType().equals("identifier")) {
                        modules.add(module(Kind.FIELD, flatten(textOf(c)), parent, contentOf(node)));
                    }
                }
            }
        }

        protected String signature(final TSNode parameters) {
            if (parameters == null || parameters.isNull()) {
                return "";
            }
            final List<String> types = new ArrayList<>();
            for (int i = 0; i < parameters.getNamedChildCount(); i++) {
                final TSNode p = parameters.getNamedChild(i);
                if (p.getType().equals("parameter_declaration")) {
                    types.add(Historage.escape(textOf(p.getChildByFieldName("type")).replaceAll("\\s+", "")));
                }
            }
            return String.join(",", types);
        }
    }

    /**
     * Walks the tree-sitter CST of a Ruby file: classes, modules (naming scopes), methods (instance
     * and singleton {@code def self.x}), and top-level constant assignments (fields).
     */
    public class RubyModuleGenerator extends ModuleGenerator {
        public RubyModuleGenerator(final String filename, final SourceText text) {
            super(filename, text, ScopedNaming.INSTANCE);
        }

        @Override
        public List<Module> run(final TSNode root) {
            walk(root, file);
            return modules;
        }

        protected void walk(final TSNode node, final Module parent) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode child = node.getNamedChild(i);
                switch (child.getType()) {
                    case "class" -> visitClass(child, parent);
                    case "module" -> visitModule(child, parent);
                    case "method", "singleton_method" -> visitMethod(child, parent);
                    case "assignment" -> visitAssignment(child, parent);
                    default -> {
                        if (!child.isError()) {
                            walk(child, parent);
                        }
                    }
                }
            }
        }

        protected void visitClass(final TSNode node, final Module parent) {
            final TSNode name = node.getChildByFieldName("name");
            if (name.isNull()) {
                return;
            }
            final Module klass = module(Kind.CLASS, flatten(textOf(name)), parent, contentOf(node));
            if (requiresClasses) {
                modules.add(klass);
            }
            final TSNode body = node.getChildByFieldName("body");
            if (!body.isNull()) {
                walk(body, klass);
            }
        }

        protected void visitModule(final TSNode node, final Module parent) {
            final TSNode name = node.getChildByFieldName("name");
            final Module scope = name.isNull() ? parent : module(Kind.CLASS, flatten(textOf(name)), parent, null);
            final TSNode body = node.getChildByFieldName("body");
            if (!body.isNull()) {
                walk(body, scope);
            }
        }

        protected void visitMethod(final TSNode node, final Module parent) {
            if (requiresMethods) {
                final TSNode name = node.getChildByFieldName("name");
                modules.add(module(Kind.METHOD, flatten(textOf(name)) + "(" + signature(node.getChildByFieldName("parameters")) + ")", parent, contentOf(node)));
            }
        }

        protected void visitAssignment(final TSNode node, final Module parent) {
            final TSNode left = node.getChildByFieldName("left");
            if (requiresFields && !left.isNull() && left.getType().equals("constant")) {
                modules.add(module(Kind.FIELD, flatten(textOf(left)), parent, contentOf(node)));
            }
        }

        protected String signature(final TSNode parameters) {
            if (parameters == null || parameters.isNull()) {
                return "";
            }
            final List<String> names = new ArrayList<>();
            for (int i = 0; i < parameters.getNamedChildCount(); i++) {
                final TSNode p = parameters.getNamedChild(i);
                final TSNode name = p.getChildByFieldName("name");
                names.add(Historage.escape(textOf(name.isNull() ? p : name).replaceAll("\\s+", "")));
            }
            return String.join(",", names);
        }
    }

    /**
     * Walks the tree-sitter CST of a Rust file: structs, enums, unions, and traits (classes, with
     * struct fields and trait methods split out), free functions, {@code impl} blocks (whose methods
     * attach to the implemented type), modules (naming scopes), and constants and statics (fields).
     */
    public class RustModuleGenerator extends ModuleGenerator {
        public RustModuleGenerator(final String filename, final SourceText text) {
            super(filename, text, ScopedNaming.INSTANCE);
        }

        @Override
        public List<Module> run(final TSNode root) {
            walk(root, file);
            return modules;
        }

        protected void walk(final TSNode node, final Module parent) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode child = node.getNamedChild(i);
                switch (child.getType()) {
                    case "struct_item", "enum_item", "union_item" -> visitType(child, parent);
                    case "trait_item" -> visitTrait(child, parent);
                    case "function_item" -> visitFunction(child, child, parent);
                    case "impl_item" -> visitImpl(child, parent);
                    case "mod_item" -> visitModule(child, parent);
                    case "const_item", "static_item", "type_item" -> visitConst(child, parent);
                    default -> {
                        if (!child.isError()) {
                            walk(child, parent);
                        }
                    }
                }
            }
        }

        protected void visitType(final TSNode node, final Module parent) {
            final TSNode name = node.getChildByFieldName("name");
            if (name.isNull()) {
                return;
            }
            final Module klass = module(Kind.CLASS, flatten(textOf(name)), parent, contentOf(node));
            if (requiresClasses) {
                modules.add(klass);
            }
            final TSNode body = node.getChildByFieldName("body");
            if (!body.isNull() && requiresFields) {
                for (int i = 0; i < body.getNamedChildCount(); i++) {
                    final TSNode f = body.getNamedChild(i);
                    if (f.getType().equals("field_declaration")) {
                        modules.add(module(Kind.FIELD, flatten(textOf(f.getChildByFieldName("name"))), klass, contentOf(f)));
                    }
                }
            }
        }

        protected void visitTrait(final TSNode node, final Module parent) {
            final TSNode name = node.getChildByFieldName("name");
            if (name.isNull()) {
                return;
            }
            final Module klass = module(Kind.CLASS, flatten(textOf(name)), parent, contentOf(node));
            if (requiresClasses) {
                modules.add(klass);
            }
            final TSNode body = node.getChildByFieldName("body");
            if (!body.isNull()) {
                for (int i = 0; i < body.getNamedChildCount(); i++) {
                    final TSNode m = body.getNamedChild(i);
                    if (m.getType().equals("function_item") || m.getType().equals("function_signature_item")) {
                        visitFunction(m, m, klass);
                    }
                }
            }
        }

        protected void visitImpl(final TSNode node, final Module parent) {
            final TSNode type = node.getChildByFieldName("type");
            final Module scope = type.isNull() ? parent
                    : module(Kind.CLASS, flatten(baseTypeName(type)), parent, null);
            final TSNode body = node.getChildByFieldName("body");
            if (!body.isNull()) {
                for (int i = 0; i < body.getNamedChildCount(); i++) {
                    final TSNode m = body.getNamedChild(i);
                    if (m.getType().equals("function_item")) {
                        visitFunction(m, m, scope);
                    }
                }
            }
        }

        protected void visitFunction(final TSNode extent, final TSNode def, final Module parent) {
            if (requiresMethods) {
                final TSNode name = def.getChildByFieldName("name");
                modules.add(module(Kind.METHOD, flatten(textOf(name)) + "(" + signature(def.getChildByFieldName("parameters")) + ")", parent, contentOf(extent)));
            }
        }

        protected void visitModule(final TSNode node, final Module parent) {
            final TSNode name = node.getChildByFieldName("name");
            final Module scope = name.isNull() ? parent : module(Kind.CLASS, flatten(textOf(name)), parent, null);
            final TSNode body = node.getChildByFieldName("body");
            if (!body.isNull()) {
                walk(body, scope);
            }
        }

        protected void visitConst(final TSNode node, final Module parent) {
            final TSNode name = node.getChildByFieldName("name");
            if (requiresFields && !name.isNull()) {
                modules.add(module(Kind.FIELD, flatten(textOf(name)), parent, contentOf(node)));
            }
        }

        /**
         * The base name of a type, dropping generic arguments, e.g. {@code Foo} for {@code Foo<T>}.
         */
        protected String baseTypeName(final TSNode type) {
            if (type.getType().equals("generic_type")) {
                final TSNode base = firstChildOfType(type, "type_identifier");
                return base != null ? textOf(base) : textOf(type);
            }
            return textOf(type);
        }

        protected String signature(final TSNode parameters) {
            if (parameters == null || parameters.isNull()) {
                return "";
            }
            final List<String> types = new ArrayList<>();
            for (int i = 0; i < parameters.getNamedChildCount(); i++) {
                final TSNode p = parameters.getNamedChild(i);
                if (p.getType().equals("parameter")) {
                    types.add(Historage.escape(textOf(p.getChildByFieldName("type")).replaceAll("\\s+", "")));
                } else if (p.getType().equals("self_parameter")) {
                    types.add("self");
                }
            }
            return String.join(",", types);
        }
    }

    /**
     * Walks the tree-sitter CST of a Kotlin file: classes, interfaces, objects, and enums (classes),
     * functions, and properties. The Kotlin grammar uses few field names, so names are found by
     * child type.
     */
    public class KotlinModuleGenerator extends ModuleGenerator {
        public KotlinModuleGenerator(final String filename, final SourceText text) {
            super(filename, text, ScopedNaming.INSTANCE);
        }

        @Override
        public List<Module> run(final TSNode root) {
            walk(root, file);
            return modules;
        }

        protected void walk(final TSNode node, final Module parent) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode child = node.getNamedChild(i);
                switch (child.getType()) {
                    case "class_declaration", "object_declaration" -> visitClass(child, parent);
                    case "function_declaration" -> visitFunction(child, parent);
                    case "property_declaration" -> visitProperty(child, parent);
                    default -> {
                        if (!child.isError()) {
                            walk(child, parent);
                        }
                    }
                }
            }
        }

        protected void visitClass(final TSNode node, final Module parent) {
            final TSNode name = firstChildOfType(node, "type_identifier");
            if (name == null) {
                return;
            }
            final Module klass = module(Kind.CLASS, flatten(textOf(name)), parent, contentOf(node));
            if (requiresClasses) {
                modules.add(klass);
            }
            final TSNode body = firstChildOfType(node, "class_body");
            if (body != null) {
                walk(body, klass);
            }
        }

        protected void visitFunction(final TSNode node, final Module parent) {
            if (!requiresMethods) {
                return;
            }
            final TSNode name = firstChildOfType(node, "simple_identifier");
            if (name != null) {
                modules.add(module(Kind.METHOD, flatten(textOf(name)) + "(" + signature(node) + ")", parent, contentOf(node)));
            }
        }

        protected void visitProperty(final TSNode node, final Module parent) {
            if (!requiresFields) {
                return;
            }
            final TSNode decl = firstChildOfType(node, "variable_declaration");
            final TSNode name = decl != null ? firstChildOfType(decl, "simple_identifier") : null;
            if (name != null) {
                modules.add(module(Kind.FIELD, flatten(textOf(name)), parent, contentOf(node)));
            }
        }

        protected String signature(final TSNode node) {
            final TSNode params = firstChildOfType(node, "function_value_parameters");
            if (params == null) {
                return "";
            }
            final List<String> types = new ArrayList<>();
            for (int i = 0; i < params.getNamedChildCount(); i++) {
                final TSNode p = params.getNamedChild(i);
                if (p.getType().equals("parameter")) {
                    final TSNode type = firstChildOfType(p, "user_type");
                    types.add(Historage.escape(textOf(type != null ? type : p).replaceAll("\\s+", "")));
                }
            }
            return String.join(",", types);
        }
    }

    /**
     * Walks the tree-sitter CST of a Swift file: classes, structs, enums (classes), protocols,
     * functions, initializers, and properties. The Swift grammar overloads field names, so names are
     * found by child type.
     */
    public class SwiftModuleGenerator extends ModuleGenerator {
        public SwiftModuleGenerator(final String filename, final SourceText text) {
            super(filename, text, ScopedNaming.INSTANCE);
        }

        @Override
        public List<Module> run(final TSNode root) {
            walk(root, file);
            return modules;
        }

        protected void walk(final TSNode node, final Module parent) {
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode child = node.getNamedChild(i);
                switch (child.getType()) {
                    case "class_declaration", "protocol_declaration" -> visitClass(child, parent);
                    case "function_declaration", "protocol_function_declaration" -> visitFunction(child, parent);
                    case "init_declaration" -> visitInit(child, parent);
                    case "property_declaration" -> visitProperty(child, parent);
                    default -> {
                        if (!child.isError()) {
                            walk(child, parent);
                        }
                    }
                }
            }
        }

        protected void visitClass(final TSNode node, final Module parent) {
            final TSNode name = firstChildOfType(node, "type_identifier");
            if (name == null) {
                return;
            }
            final Module klass = module(Kind.CLASS, flatten(textOf(name)), parent, contentOf(node));
            if (requiresClasses) {
                modules.add(klass);
            }
            for (final String bodyType : new String[] {"class_body", "enum_class_body", "protocol_body"}) {
                final TSNode body = firstChildOfType(node, bodyType);
                if (body != null) {
                    walk(body, klass);
                }
            }
        }

        protected void visitFunction(final TSNode node, final Module parent) {
            if (!requiresMethods) {
                return;
            }
            final TSNode name = firstChildOfType(node, "simple_identifier");
            if (name != null) {
                modules.add(module(Kind.METHOD, flatten(textOf(name)) + "(" + signature(node) + ")", parent, contentOf(node)));
            }
        }

        protected void visitInit(final TSNode node, final Module parent) {
            if (requiresMethods) {
                modules.add(module(Kind.METHOD, "init(" + signature(node) + ")", parent, contentOf(node)));
            }
        }

        protected void visitProperty(final TSNode node, final Module parent) {
            if (!requiresFields) {
                return;
            }
            final TSNode pattern = node.getChildByFieldName("name");
            final TSNode name = pattern.isNull() ? null : pattern.getChildByFieldName("bound_identifier");
            if (name != null && !name.isNull()) {
                modules.add(module(Kind.FIELD, flatten(textOf(name)), parent, contentOf(node)));
            }
        }

        protected String signature(final TSNode node) {
            final List<String> names = new ArrayList<>();
            for (int i = 0; i < node.getNamedChildCount(); i++) {
                final TSNode p = node.getNamedChild(i);
                if (p.getType().equals("parameter")) {
                    final TSNode name = firstChildOfType(p, "simple_identifier");
                    names.add(Historage.escape(textOf(name != null ? name : p).replaceAll("\\s+", "")));
                }
            }
            return String.join(",", names);
        }
    }
}
