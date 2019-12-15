package jp.ac.titech.c.se.stein.app;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Stack;
import java.util.stream.Collectors;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;
import org.eclipse.jgit.lib.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.CLI;
import jp.ac.titech.c.se.stein.core.Config;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.EntrySet;
import jp.ac.titech.c.se.stein.core.EntrySet.Entry;
import jp.ac.titech.c.se.stein.core.EntrySet.EntryList;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;

/**
 * A simple Historage generator with FinerGit-compatible naming convention.
 *
 * @see https://github.com/hideakihata/git2historage
 * @see https://github.com/kusumotolab/FinerGit
 */
public class Historage extends RepositoryRewriter {
    private static final Logger log = LoggerFactory.getLogger(Historage.class);

    protected boolean requiresFields = true;

    protected boolean requiresClasses = true;

    protected boolean requiresDocs = true;

    protected boolean requiresOriginals = true;

    protected boolean requiresNonCode = true;

    @Override
    public void addOptions(final Config conf) {
        super.addOptions(conf);
        conf.addOption(null, "no-fields", false, "exclude field files");
        conf.addOption(null, "no-classes", false, "exclude class files");
        conf.addOption(null, "no-docs", false, "exclude documentation files");
        conf.addOption(null, "no-original", false, "exclude original files");
        conf.addOption(null, "no-noncode", false, "exclude non-code files");
    }

    @Override
    public void configure(final Config conf) {
        super.configure(conf);
        this.requiresFields = !conf.hasOption("no-fields");
        this.requiresClasses = !conf.hasOption("no-classes");
        this.requiresDocs = !conf.hasOption("no-docs");
        this.requiresOriginals = !conf.hasOption("no-original");
        this.requiresNonCode = !conf.hasOption("no-noncode");
    }

    @Override
    public EntrySet rewriteEntry(final Entry entry, final Context c) {
        if (entry.isTree()) {
            return super.rewriteEntry(entry, c);
        }
        if (!entry.name.toLowerCase().endsWith(".java")) {
            return requiresNonCode ? super.rewriteEntry(entry, c) : EntrySet.EMPTY;
        }

        final EntryList result = new EntryList();
        if (requiresOriginals) {
            result.add(entry);
        }
        final String source = new String(in.readBlob(entry.id, c), StandardCharsets.UTF_8);
        for (final Module m : new ModuleGenerator(entry.name, source).generate()) {
            final ObjectId newId = out.writeBlob(m.getContent().getBytes(StandardCharsets.UTF_8), c);
            log.debug("Generate module: {} [{}] from {} ({})", m.getFilename(), newId.name(), entry, c);
            result.add(new Entry(entry.mode, m.getFilename(), newId, entry.directory));
        }
        return result;
    }

    protected ASTParser createParser() {
        final ASTParser parser = ASTParser.newParser(AST.JLS11);
        @SuppressWarnings("unchecked")
        final Map<String, String> options = DefaultCodeFormatterConstants.getEclipseDefaultSettings();
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_1_8);
        options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_1_8);
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_1_8);
        options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
        parser.setCompilerOptions(options);
        parser.setEnvironment(null, null, null, true);
        return parser;
    }

    public static abstract class Module {
        protected final String name;
        protected final String extension;
        protected final Module parent;
        protected final String content;

        public Module(final String name, final String extension, final Module parent, final String content) {
            this.name = name;
            this.extension = extension;
            this.parent = parent;
            this.content = content;
        }

        public String getBasename() {
            return name;
        }

        public String getFilename() {
            return getBasename() + extension;
        }

        public String getContent() {
            return content;
        }

        public static class File extends Module {
            public File(final String name) {
                super(name, null, null, null);
            }
        }

        public static class Class extends Module {
            public Class(final String name, final Module parent, final String content) {
                super(name, ".cjava", parent, content);
            }
            @Override
            public String getBasename() {
                if (parent instanceof Class) {
                    return parent.getBasename() + "." + name;
                } else {
                    return parent.getBasename().equals(name) ? name : name + "[" + parent.getBasename() + "]";
                }
            }
        }

        public static class Method extends Module {
            public Method(final String name, final Module parent, final String content) {
                super(name, ".mjava", parent, content);
            }
            @Override
            public String getBasename() {
                return parent.getBasename() + "#" + name;
            }
        }

        public static class Field extends Module {
            public Field(final String name, final Module parent, final String content) {
                super(name, ".fjava", parent, content);
            }
            @Override
            public String getBasename() {
                return parent.getBasename() + "#" + name;
            }
        }

        public static class Javadoc extends Module {
            public Javadoc(final Module parent, final String content) {
                super(null, ".doc", parent, content);
            }
            @Override
            public String getBasename() {
                return parent.getFilename();
            }
        }
    }

    public class ModuleGenerator extends ASTVisitor {
        private final String source;
        private final Stack<Module> stack = new Stack<>();
        private final List<Module> modules = new ArrayList<>();

        public ModuleGenerator(final String filename, final String source) {
            this.source = source;
            final String basename = filename.substring(0, filename.lastIndexOf('.'));
            stack.push(new Module.File(basename));
        }

        public List<Module> generate() {
            final CompilationUnit unit = parse();
            if (unit == null) {
                return Collections.emptyList();
            } else {
                unit.accept(this);
                return modules;
            }
        }

        protected CompilationUnit parse() {
            final ASTParser parser = createParser();
            parser.setSource(source.toCharArray());
            final CompilationUnit unit = (CompilationUnit) parser.createAST(null);
            final IProblem[] problems = unit.getProblems();
            if (problems == null || problems.length > 0) {
                return null;
            } else {
                return unit;
            }
        }

        protected ASTParser createParser() {
            final ASTParser parser = ASTParser.newParser(AST.JLS11);
            @SuppressWarnings("unchecked")
            final Map<String, String> options = DefaultCodeFormatterConstants.getEclipseDefaultSettings();
            options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_1_8);
            options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_1_8);
            options.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_1_8);
            options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
            parser.setCompilerOptions(options);
            parser.setEnvironment(null, null, null, true);
            return parser;
        }

        protected String getSource(final int start, final int end) {
            // include the prefix indent
            int prefix = 0;
            LOOP: while (start > prefix) {
                switch (source.charAt(start - prefix - 1)) {
                case ' ':
                case '\t':
                    prefix++;
                    continue;
                case '\r':
                case '\n':
                    break LOOP;
                default:
                    prefix = 0;
                    break LOOP;
                }
            }

            final String content = source.substring(start - prefix, end);
            return source.charAt(end - 1) == '\n' ? content : content + "\n";
        }

        protected String getSource(final ASTNode node) {
            return getSource(node.getStartPosition(), node.getStartPosition() + node.getLength());
        }

        protected String getSourceWithoutJavadoc(final BodyDeclaration node) {
            final Optional<Integer> start = findChildNodes(node).stream()
                    .filter(n -> !(n instanceof Javadoc))
                    .map(n -> n.getStartPosition())
                    .min(Comparator.naturalOrder());
            if (start.isPresent() && start.get() > node.getStartPosition()) {
                return getSource(start.get(), node.getStartPosition() + node.getLength());
            } else {
                return getSource(node);
            }
        }

        protected List<ASTNode> findChildNodes(final ASTNode node) {
            final List<ASTNode> result = new ArrayList<>();
            node.accept(new ASTVisitor() {
                boolean isRoot = true;
                @Override
                public boolean preVisit2(final ASTNode node) {
                    if (isRoot) {
                        isRoot = false;
                        return true;
                    }
                    result.add(node);
                    return false;
                }
            });
            return result;
        }

        @Override
        public boolean visit(final TypeDeclaration node) {
            return visitType(node);
        }

        @Override
        public boolean visit(final EnumDeclaration node) {
            return visitType(node);
        }

        @Override
        public boolean visit(final AnnotationTypeDeclaration node) {
            return visitType(node);
        }

        @Override
        public void endVisit(final TypeDeclaration node) {
            endVisitType(node);
        }

        @Override
        public void endVisit(final EnumDeclaration node) {
            endVisitType(node);
        }

        @Override
        public void endVisit(final AnnotationTypeDeclaration node) {
            endVisitType(node);
        }

        protected boolean visitType(final AbstractTypeDeclaration node) {
            final String name = node.getName().getIdentifier();
            final Module klass = new Module.Class(name, stack.peek(), getSourceWithoutJavadoc(node));
            if (requiresClasses) {
                modules.add(klass);
                if (requiresDocs && node.getJavadoc() != null) {
                    modules.add(new Module.Javadoc(klass, getSource(node.getJavadoc())));
                }
            }
            stack.push(klass);
            return true;
        }

        protected void endVisitType(final AbstractTypeDeclaration node) {
            stack.pop();
        }

        @Override
        public boolean visit(final AnonymousClassDeclaration node) {
            return false;
        }

        @Override
        public boolean visit(final MethodDeclaration node) {
            final String name = new MethodNameGenerator(node).generate();
            final Module method = new Module.Method(name, stack.peek(), getSourceWithoutJavadoc(node));
            modules.add(method);
            if (requiresDocs && node.getJavadoc() != null) {
                modules.add(new Module.Javadoc(method, getSource(node.getJavadoc())));
            }
            return false;
        }

        @Override
        public boolean visit(final FieldDeclaration node) {
            for (final Object f : node.fragments()) {
                final String name = ((VariableDeclarationFragment) f).getName().toString();
                final Module field = new Module.Field(name, stack.peek(), getSourceWithoutJavadoc(node));
                if (requiresFields) {
                    modules.add(field);
                    if (requiresDocs && node.getJavadoc() != null) {
                        modules.add(new Module.Javadoc(field, getSource(node.getJavadoc())));
                    }
                }
            }
            return false;
        }
    }

    public class MethodNameGenerator {
        private final MethodDeclaration node;
        private final StringBuilder buffer = new StringBuilder();

        public MethodNameGenerator(final MethodDeclaration node) {
            this.node = node;
        }

        public String generate() {
            generateTypeParameters();
            // generateReturnType();
            generateName();
            generateParameters();
            return buffer.toString();
        }

        protected void generateTypeParameters() {
            @SuppressWarnings("unchecked")
            final List<Object> types = node.typeParameters();
            if (types != null && !types.isEmpty()) {
                final String typenames = types.stream()
                        .map(o -> escape(o.toString()))
                        .collect(Collectors.joining(","));
                buffer.append("[").append(typenames).append("]_");
            }
        }

        protected void generateReturnType() {
            final Type type = node.getReturnType2();
            if (type != null) {
                buffer.append(escape(type.toString())).append("_");
            }
        }

        protected void generateName() {
            buffer.append(node.getName().getIdentifier());
        }

        protected void generateParameters() {
            @SuppressWarnings("unchecked")
            final List<Object> params = node.parameters();
            final String names = params.stream()
                    .map(o -> getTypeName((SingleVariableDeclaration) o))
                    .collect(Collectors.joining(","));
            buffer.append("(").append(names).append(")");
        }

        protected String getTypeName(final SingleVariableDeclaration v) {
            final StringBuilder sb = new StringBuilder();
            sb.append(escape(v.getType().toString()));
            for (int i = 0; i < v.getExtraDimensions(); i++) {
                sb.append("[]");
            }
            if (v.isVarargs()) {
                sb.append("...");
            }
            return sb.toString();
        }

        protected String escape(final String s) {
            return s.replace(' ', '-')
                    .replace('?', '#')
                    .replace('<', '[')
                    .replace('>', ']');
        }
    }

    public static void main(final String[] args) {
        CLI.execute(new Historage(), args);
    }
}
