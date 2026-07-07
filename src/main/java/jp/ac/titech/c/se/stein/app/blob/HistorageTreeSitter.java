package jp.ac.titech.c.se.stein.app.blob;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import jp.ac.titech.c.se.stein.util.HashUtils;
import jp.ac.titech.c.se.stein.util.PythonSource;
import lombok.AllArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSTree;
import org.treesitter.TreeSitterPython;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * A Historage generator using tree-sitter, currently supporting Python.
 * Splits source files into finer-grained modules (one file per class, function, or field),
 * mirroring {@link HistorageJdt} for Java.
 *
 * <p>Since tree-sitter parses in an error-tolerant way, modules are extracted even from
 * files that contain syntax errors elsewhere (e.g., historical Python 2 code).</p>
 */
@Slf4j
@ToString
@Command(name = "@historage-ts", description = "Generate finer-grained Python modules via tree-sitter")
public class HistorageTreeSitter implements BlobTranslator {
    public static final NameFilter PYTHON = new NameFilter(true, "*.py");

    @Option(names = "--no-original", negatable = true, description = "Exclude original files")
    protected boolean requiresOriginals = true;

    @Option(names = "--no-classes", negatable = true, description = "[ex]/include class files")
    protected boolean requiresClasses = true;

    @Option(names = "--no-methods", negatable = true, description = "[ex]/include function files")
    protected boolean requiresMethods = true;

    @Option(names = "--no-fields", negatable = true, description = "[ex]/include field files")
    protected boolean requiresFields = true;

    @Option(names = "--class-ext", paramLabel = "<ext>", description = "class file extension (default: ${DEFAULT-VALUE})")
    protected String classExtension = ".cpy";

    @Option(names = "--method-ext", paramLabel = "<ext>", description = "function file extension (default: ${DEFAULT-VALUE})")
    protected String methodExtension = ".mpy";

    @Option(names = "--field-ext", paramLabel = "<ext>", description = "field file extension (default: ${DEFAULT-VALUE})")
    protected String fieldExtension = ".fpy";

    /**
     * Tree-sitter parsers are not thread-safe; one per thread.
     */
    private static final ThreadLocal<TSParser> PARSER = ThreadLocal.withInitial(() -> {
        final TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterPython());
        return parser;
    });

    @Override
    public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
        if (!PYTHON.accept(entry)) {
            return entry;
        }
        final AnyHotEntry.Set result = AnyHotEntry.set();
        if (requiresOriginals) {
            result.add(entry);
        }
        final SourceText text = PythonSource.decode(entry.getBlob());
        final List<Module> modules = new ModuleGenerator(baseName(entry.getName()), text).generate();
        if (!modules.isEmpty()) {
            resolveNameConflicts(modules);
            for (final Module m : modules) {
                log.debug("Generate submodule: {} from {} {}", m.getFilename(), entry, c);
                result.add(HotEntry.of(entry.getMode(), m.getFilename(), m.getBlob()));
            }
            log.debug("Rewrite entry: {} -> {} entries {}", entry, result.size(), c);
        }
        return result;
    }

    protected static String baseName(final String filename) {
        final int index = filename.lastIndexOf('.');
        return index > 0 ? filename.substring(0, index) : filename;
    }

    /**
     * Appends {@code @2}, {@code @3}, ... to the second and later occurrences of the same filename.
     */
    protected void resolveNameConflicts(final List<Module> modules) {
        final Map<String, Integer> counter = new HashMap<>();
        for (final Module m : modules) {
            final int count = counter.merge(m.getFilename(), 1, Integer::sum);
            if (count >= 2) {
                m.index = count;
            }
        }
    }

    /**
     * A generated Historage module representing a class or a function.
     */
    @AllArgsConstructor
    public abstract static class Module {
        protected final String name;
        protected final String extension;
        protected final Module parent;
        protected final String content;

        protected int index = 1;

        public Module(final String name, final String extension, final Module parent, final String content) {
            this(name, extension, parent, content, 1);
        }

        /**
         * File systems commonly limit a file name to 255 bytes; leave room for the suffixes.
         */
        private static final int MAX_BASENAME_BYTES = 200;

        public abstract String getBasename();

        public String getFilename() {
            String basename = getBasename();
            if (basename.getBytes(StandardCharsets.UTF_8).length > MAX_BASENAME_BYTES) {
                final String digest = "~" + HashUtils.digest(basename, 6);
                basename = truncateToBytes(basename, MAX_BASENAME_BYTES - digest.length()) + digest;
            }
            return basename + (index >= 2 ? "@" + index : "") + extension;
        }

        private static String truncateToBytes(final String s, final int maxBytes) {
            String result = s;
            while (result.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        }

        public byte[] getBlob() {
            return content.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * A virtual root module representing the source file itself.
     */
    public static class FileModule extends Module {
        public FileModule(final String name) {
            super(name, null, null, null);
        }

        @Override
        public String getBasename() {
            return name;
        }
    }

    public class ClassModule extends Module {
        public ClassModule(final String name, final Module parent, final String content) {
            super(name, classExtension, parent, content);
        }

        @Override
        public String getBasename() {
            final String separator = parent instanceof FileModule ? "!" : ".";
            return parent.getBasename() + separator + name;
        }
    }

    public class FunctionModule extends Module {
        public FunctionModule(final String name, final Module parent, final String content) {
            super(name, methodExtension, parent, content);
        }

        @Override
        public String getBasename() {
            final String separator = parent instanceof FileModule ? "!" : "#";
            return parent.getBasename() + separator + name;
        }
    }

    public class FieldModule extends Module {
        public FieldModule(final String name, final Module parent, final String content) {
            super(name, fieldExtension, parent, content);
        }

        @Override
        public String getBasename() {
            final String separator = parent instanceof FileModule ? "!" : "#";
            return parent.getBasename() + separator + name;
        }
    }

    /**
     * Walks the tree-sitter CST and generates {@link Module} instances for classes and functions.
     * Descends into class bodies and statement blocks, but not into function bodies.
     */
    public class ModuleGenerator {
        private final SourceText text;

        private final List<Module> modules = new ArrayList<>();

        private final FileModule file;

        public ModuleGenerator(final String basename, final SourceText text) {
            this.text = text;
            this.file = new FileModule(basename);
        }

        /**
         * Generates a list of Historage modules.
         */
        public List<Module> generate() {
            final TSTree tree = PARSER.get().parseString(null, text.getContent());
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
            final Module klass = new ClassModule(name, parent, contentOf(extent));
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
                modules.add(new FunctionModule(name + "(" + signature + ")", parent, contentOf(extent)));
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
                modules.add(new FieldModule(textOf(left), parent, contentOf(statement)));
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
}
