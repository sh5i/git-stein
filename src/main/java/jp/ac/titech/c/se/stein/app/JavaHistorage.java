package jp.ac.titech.c.se.stein.app;

import java.util.*;
import java.util.stream.Collectors;

import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.core.SourceText.Fragment;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
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
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.ColdEntry.HashEntry;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * A simple Historage generator with FinerGit-compatible naming convention.
 *
 * @see <a href="https://github.com/hideakihata/git2historage">git2historage</a>
 * @see <a href="https://github.com/kusumotolab/FinerGit">FinerGit</a>
 */
@Slf4j
@Command(name = "java-historage", description = "Generate finer-grained Java modules")
public class JavaHistorage extends Extractor {
    @Option(names = "--no-classes", negatable = true, description = "[ex]/include class files")
    protected boolean requiresClasses = true;

    @Option(names = "--no-fields", negatable = true, description = "[ex]/include field files")
    protected boolean requiresFields = true;

    @Option(names = "--no-methods", negatable = true, description = "[ex]/include method files")
    protected boolean requiresMethods = true;

    @Option(names = "--comments", description = "extract comment files")
    protected boolean requiresComments = false;

    @Option(names = "--separate-comments", description = "exclude comments from modules")
    protected boolean separatesComments = false;

    @Option(names = "--class-ext", paramLabel = "<ext>", description = "class file extension (default: ${DEFAULT-VALUE})")
    protected String classExtension = ".cjava";

    @Option(names = "--method-ext", paramLabel = "<ext>", description = "method file extension (default: ${DEFAULT-VALUE})")
    protected String methodExtension = ".mjava";

    @Option(names = "--field-ext", paramLabel = "<ext>", description = "field file extension (default: ${DEFAULT-VALUE})")
    protected String fieldExtension = ".fjava";

    @Option(names = "--comment-ext", paramLabel = "<ext>", description = "comment file extension (default: ${DEFAULT-VALUE})")
    protected String commentExtension = ".com";

    @Option(names = "--digest-params", description = "digest parameters")
    protected boolean digestParameters = false;

    @Option(names = "--unqualify", description = "unqualify typenames")
    protected boolean unqualifyTypename = false;

    @Option(names = "--parsable", description = "generate more parsable files")
    protected boolean parsable = false;

    @Override
    protected boolean accept(final String filename) {
        return filename.endsWith(".java");
    }

    @Override
    protected Collection<Module> generate(final HashEntry entry, final SourceText text, final Context c) {
        return new ModuleGenerator(entry.name, text).generate();
    }

    @AllArgsConstructor
    public abstract static class Module implements Extractor.Module {
        protected final String name;
        protected final String extension;
        protected final Module parent;

        @Getter
        protected final String content;

        public String getBasename() {
            return name;
        }

        @Override
        public String getFilename() {
            return getBasename() + extension;
        }
    }

    public static class FileModule extends Module {
        public FileModule(final String name) {
            super(name, null, null, null);
        }
    }

    public class ClassModule extends Module {
        public ClassModule(final String name, final Module parent, final String content) {
            super(name, classExtension, parent, content);
        }

        @Override
        public String getBasename() {
            if (parent instanceof ClassModule) {
                return parent.getBasename() + "." + name;
            } else {
                return parent.getBasename().equals(name) ? name : name + "[" + parent.getBasename() + "]";
            }
        }
    }

    public class MethodModule extends Module {
        public MethodModule(final String name, final Module parent, final String content) {
            super(name, methodExtension, parent, content);
        }

        @Override
        public String getBasename() {
            return parent.getBasename() + "#" + name;
        }
    }

    public class FieldModule extends Module {
        public FieldModule(final String name, final Module parent, final String content) {
            super(name, fieldExtension, parent, content);
        }

        @Override
        public String getBasename() {
            return parent.getBasename() + "#" + name;
        }
    }

    public class CommentModule extends Module {
        public CommentModule(final Module parent, final String content) {
            super(null, commentExtension, parent, content);
        }

        @Override
        public String getBasename() {
            return parent.getFilename();
        }
    }

    public static class CommentSet {
        private final CompilationUnit unit;
        private final List<Comment> comments;
        private final List<Integer> offsets;
        private final Map<ASTNode, List<Comment>> cache = new HashMap<>();

        public CommentSet(final CompilationUnit unit) {
            this.unit = unit;
            @SuppressWarnings("unchecked")
            final List<Comment> comments = unit != null ? unit.getCommentList() : Collections.emptyList();
            this.comments = comments;
            this.offsets = comments.stream().map(ASTNode::getStartPosition).collect(Collectors.toList());
        }

        public List<Comment> getComments(final ASTNode node) {
            return cache.computeIfAbsent(node, this::extractComments);
        }

        protected List<Comment> extractComments(final ASTNode node) {
            final int leading = unit.firstLeadingCommentIndex(node);
            final int start = leading != -1 ? leading : lookup(node.getStartPosition());
            final int trailing = unit.lastTrailingCommentIndex(node);
            final int end = trailing != -1 ? trailing + 1 : lookup(node.getStartPosition() + node.getLength());
            return comments.subList(start, end); // [start, end)
        }

        protected int lookup(final int offset) {
            final int index = Collections.binarySearch(offsets, offset);
            return index >= 0 ? index : ~index;
        }
    }

    public class ModuleGenerator extends ASTVisitor {
        private final SourceText text;
        private final CompilationUnit unit;
        private final Stack<Module> stack = new Stack<>();
        private final List<Module> modules = new ArrayList<>();
        private final CommentSet commentSet;

        public ModuleGenerator(final String filename, final SourceText text) {
            this.text = text;
            final String basename = filename.substring(0, filename.lastIndexOf('.'));
            stack.push(new FileModule(basename));
            this.unit = parse();
            this.commentSet = new CommentSet(this.unit);
        }

        /**
         * Generates a list of Historage modules.
         */
        public List<Module> generate() {
            if (unit == null) {
                return Collections.emptyList();
            }
            unit.accept(this);
            return modules;
        }

        /**
         * Parses the given source string.
         */
        protected CompilationUnit parse() {
            final ASTParser parser = createParser();
            parser.setSource(text.getContent().toCharArray());
            final CompilationUnit unit = (CompilationUnit) parser.createAST(null);
            final IProblem[] problems = unit.getProblems();
            if (problems == null || problems.length > 0) {
                return null;
            } else {
                return unit;
            }
        }

        /**
         * Creates a JDT ASTParser.
         */
        protected ASTParser createParser() {
            final ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            @SuppressWarnings("unchecked")
            final Map<String, String> options = DefaultCodeFormatterConstants.getEclipseDefaultSettings();
            options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_18);
            options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_18);
            options.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_18);
            options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
            parser.setCompilerOptions(options);
            parser.setEnvironment(null, null, null, true);
            return parser;
        }

        /**
         * Gets a fragment of the given range.
         */
        protected Fragment getFragment(final int start, final int end) {
            return text.getFragment(start, end);
        }

        /**
         * Gets a node fragment.
         */
        protected Fragment getFragment(final ASTNode node) {
            return getFragment(node.getStartPosition(), node.getStartPosition() + node.getLength());
        }

        /**
         * Gets a fragment of two nodes.
         */
        protected Fragment getFragment(final ASTNode startNode, final ASTNode endNode) {
            return getFragment(startNode.getStartPosition(), endNode.getStartPosition() + endNode.getLength());
        }

        /**
         * Gets a node fragment with including surrounding comments.
         */
        protected Fragment getFragmentWithSurroundingComments(final BodyDeclaration node) {
            final int leading = unit.firstLeadingCommentIndex(node);
            final int trailing = unit.lastTrailingCommentIndex(node);
            if (leading == -1 && trailing == -1) {
                return getFragment(node);
            }
            final List<?> comments = unit.getCommentList();
            final ASTNode startNode = leading != -1 ? (Comment) comments.get(leading) : node;
            final ASTNode endNode = trailing != -1 ? (Comment) comments.get(trailing) : node;
            return getFragment(startNode, endNode);
        }

        /**
         * Gets a node fragment with excluding its Javadoc comment.
         */
        @SuppressWarnings("unused")
        protected Fragment getFragmentWithoutJavadoc(final BodyDeclaration node) {
            final Optional<Integer> start = findChildNodes(node).stream()
                    .filter(n -> !(n instanceof Javadoc))
                    .map(ASTNode::getStartPosition)
                    .min(Comparator.naturalOrder());
            if (start.isPresent() && start.get() > node.getStartPosition()) {
                return getFragment(start.get(), node.getStartPosition() + node.getLength());
            } else {
                return getFragment(node);
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

        /**
         * Gets the source string of a comment.
         */
        protected String getCommentBody(final Comment c) {
            final Fragment f = getFragment(c);
            final String body = f.getExactContent();
            if (c.isLineComment()) {
                return body;
            }
            final int breaks = StringUtils.countMatches(body, "\n");
            if (breaks == 0) {
                return body; // single line
            }
            final String indent = f.getIndent();
            if (!indent.isEmpty() && StringUtils.countMatches(body, "\n" + indent) == breaks) {
                // if all lines have the same indents, then remove it.
                return body.replace("\n" + indent, "\n");
            }
            return body;
        }

        /**
         * Gets a source of the given node with excluding its all comments.
         */
        protected String getSourceWithoutComments(final BodyDeclaration node) {
            final Fragment fragment = getFragmentWithSurroundingComments(node);
            String source = fragment.getWiderContent();
            final List<Comment> comments = commentSet.getComments(node);
            for (int i = comments.size() - 1; i >= 0; i--) {
                final Fragment c = getFragment(comments.get(i));
                final int localStart = c.getWiderBegin() - fragment.getWiderBegin();
                final int localEnd = c.getWiderEnd() - fragment.getWiderBegin();
                source = source.substring(0, localStart) + source.substring(localEnd);
            }
            return source;
        }

        /**
         * Gets the comment content of the given node.
         */
        protected String getCommentContent(final BodyDeclaration node) {
            final StringBuilder sb = new StringBuilder();
            for (final Comment c : commentSet.getComments(node)) {
                sb.append(getCommentBody(c)).append("\n");
            }
            return sb.toString();
        }

        /**
         * Gets the source content of the given node.
         */
        protected String getSource(final BodyDeclaration node) {
            return separatesComments ? getSourceWithoutComments(node) : getFragmentWithSurroundingComments(node).getWiderContent();
        }

        /**
         * Gets the content of the given node. If an option requested, code of
         * its belonging package and class is supplied to make it more parsable.
         */
        protected String getContent(final BodyDeclaration node) {
            if (!parsable) {
                return getSource(node);
            }

            final StringBuilder sb = new StringBuilder();
            final PackageDeclaration pkg = unit.getPackage();
            if (pkg != null) {
                sb.append("package ").append(pkg.getName().getFullyQualifiedName()).append(";\n");
            }
            if (node instanceof TypeDeclaration) {
                sb.append(getSource(node));
            } else {
                sb.append("class ").append(stack.peek().getBasename()).append(" {\n");
                sb.append(getSource(node));
                sb.append("}\n");
            }
            return sb.toString();
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
            final Module klass = new ClassModule(name, stack.peek(), getContent(node));
            if (requiresClasses) {
                modules.add(klass);
                if (requiresComments) {
                    modules.add(new CommentModule(klass, getCommentContent(node)));
                }
            }
            stack.push(klass);
            return true;
        }

        protected void endVisitType(@SuppressWarnings("unused") final AbstractTypeDeclaration node) {
            stack.pop();
        }

        @Override
        public boolean visit(final AnonymousClassDeclaration node) {
            return false;
        }

        @Override
        public boolean visit(final MethodDeclaration node) {
            if (requiresMethods) {
                final String name = new MethodNameGenerator(node).generate();
                final Module method = new MethodModule(name, stack.peek(), getContent(node));
                modules.add(method);
                if (requiresComments) {
                    modules.add(new CommentModule(method, getCommentContent(node)));
                }
            }
            return false;
        }

        @Override
        public boolean visit(final FieldDeclaration node) {
            if (requiresFields) {
                for (final Object f : node.fragments()) {
                    final String name = ((VariableDeclarationFragment) f).getName().toString();
                    final Module field = new FieldModule(name, stack.peek(), getContent(node));
                    modules.add(field);
                    if (requiresComments) {
                        modules.add(new CommentModule(field, getCommentContent(node)));
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

        @SuppressWarnings("unused")
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
            String names = params.stream()
                    .map(o -> getTypeName((SingleVariableDeclaration) o))
                    .collect(Collectors.joining(","));
            if (digestParameters && !names.isEmpty()) {
                names = "~" + digest(names, 6);
            }
            buffer.append("(").append(names).append(")");
        }

        protected String getTypeName(final SingleVariableDeclaration v) {
            final StringBuilder sb = new StringBuilder();
            String name = v.getType().toString();
            if (unqualifyTypename && name.contains(".")) {
                name = name.replaceAll("[a-zA-Z0-9_$]+\\.", "");
            }
            sb.append(escape(name));
            sb.append("[]".repeat(Math.max(0, v.getExtraDimensions())));
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
}
