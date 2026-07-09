package jp.ac.titech.c.se.stein.analyzer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;

import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.core.SourceText.Fragment;

/**
 * A {@link SourceAnalyzer} backed by Eclipse JDT, for Java. It parses the file to a JDT AST and
 * extracts an {@link Element} tree of classes, methods, and fields with FinerGit-compatible names
 * (using JDT's resolved bindings to optionally digest parameter types or unqualify type names), and
 * renders each element's source (optionally excluding its comments or wrapping a member so it parses
 * standalone). Comment text and line numbers, which a Historage consumer needs for comment and mapping
 * side files, are exposed beyond the {@link SourceAnalyzer} contract.
 */
public class JdtAnalyzer implements SourceAnalyzer {
    private final SourceText text;

    private final CompilationUnit unit;

    private final Element root;

    private final Map<Element, BodyDeclaration> nodes = new HashMap<>();

    private final Map<Element, String> enclosingClass = new HashMap<>();

    private final CommentSet commentSet;

    private final boolean separatesComments;

    private final boolean parsable;

    private boolean extracted;

    private JdtAnalyzer(final String filename, final SourceText text, final CompilationUnit unit,
                        final boolean separatesComments, final boolean parsable) {
        this.text = text;
        this.unit = unit;
        this.separatesComments = separatesComments;
        this.parsable = parsable;
        this.root = new Element(Element.Kind.FILE, filename.substring(0, filename.lastIndexOf('.')));
        this.commentSet = new CommentSet(unit);
    }

    /**
     * Parses the blob with JDT and returns a ready analyzer, or null when the file has any compile
     * problem (JDT extracts nothing from a file it cannot fully parse).
     */
    public static JdtAnalyzer of(final String filename, final byte[] blob,
                                 final boolean separatesComments, final boolean parsable) {
        final SourceText text = SourceText.ofNormalized(blob);
        final CompilationUnit unit = parse(text);
        return unit == null ? null
                : new JdtAnalyzer(filename, text, unit, separatesComments, parsable);
    }

    /**
     * Whether this analyzer handles the given file (Java, matched case-insensitively by extension).
     */
    public static boolean accepts(final String filename) {
        return filename.toLowerCase(Locale.ROOT).endsWith(".java");
    }

    private static CompilationUnit parse(final SourceText text) {
        final ASTParser parser = ASTParser.newParser(AST.JLS25);
        final Map<String, String> options = DefaultCodeFormatterConstants.getEclipseDefaultSettings();
        // the latest source level, so that files using newer syntax are not skipped entirely
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.latestSupportedJavaVersion());
        options.put(JavaCore.COMPILER_DOC_COMMENT_SUPPORT, JavaCore.ENABLED);
        parser.setCompilerOptions(options);
        parser.setEnvironment(null, null, null, true);
        parser.setSource(text.getContent().toCharArray());
        final CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        final IProblem[] problems = unit.getProblems();
        return problems == null || problems.length > 0 ? null : unit;
    }

    @Override
    public Element extract() {
        if (!extracted) {
            unit.accept(new Builder());
            extracted = true;
        }
        return root;
    }

    @Override
    public String rawText(final Element e) {
        final BodyDeclaration node = nodes.get(e);
        return getContent(getFragmentWithSurroundingComments(node), node, enclosingClass.get(e));
    }

    @Override
    public String commentText(final Element e) {
        final StringBuilder sb = new StringBuilder();
        for (final Comment c : commentSet.getComments(nodes.get(e))) {
            sb.append(getCommentBody(c)).append("\n");
        }
        return sb.toString();
    }

    private final class Builder extends ASTVisitor {
        private final Deque<Element> stack = new ArrayDeque<>(List.of(root));

        private Element add(final Element.Kind kind, final Signature signature, final BodyDeclaration node) {
            final Fragment f = getFragmentWithSurroundingComments(node);
            final Element e = new Element(kind, signature, f.getBegin(), f.getEnd());
            e.setStartLine(unit.getLineNumber(f.getBegin()));
            e.setEndLine(unit.getLineNumber(f.getEnd()));
            stack.peek().addChild(e);
            nodes.put(e, node);
            if (kind != Element.Kind.CLASS) {
                enclosingClass.put(e, stack.peek().getName());
            }
            return e;
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
        public boolean visit(final RecordDeclaration node) {
            return visitType(node);
        }

        private boolean visitType(final AbstractTypeDeclaration node) {
            stack.push(add(Element.Kind.CLASS, Signature.of(node.getName().getIdentifier()), node));
            return true;
        }

        @Override
        public void endVisit(final TypeDeclaration node) {
            stack.pop();
        }

        @Override
        public void endVisit(final EnumDeclaration node) {
            stack.pop();
        }

        @Override
        public void endVisit(final AnnotationTypeDeclaration node) {
            stack.pop();
        }

        @Override
        public void endVisit(final RecordDeclaration node) {
            stack.pop();
        }

        @Override
        public boolean visit(final AnonymousClassDeclaration node) {
            return false;
        }

        @Override
        public boolean visit(final MethodDeclaration node) {
            add(Element.Kind.METHOD, methodSignature(node), node);
            return false;
        }

        @Override
        public boolean visit(final FieldDeclaration node) {
            for (final Object f : node.fragments()) {
                add(Element.Kind.FIELD, Signature.of(((VariableDeclarationFragment) f).getName().toString()), node);
            }
            return false;
        }
    }

    // --- rendering ---

    private String getContent(final Fragment fragment, final BodyDeclaration node, final String enclosingClass) {
        if (!parsable) {
            return getSource(fragment, node);
        }
        final StringBuilder sb = new StringBuilder();
        final PackageDeclaration pkg = unit.getPackage();
        if (pkg != null) {
            sb.append("package ").append(pkg.getName().getFullyQualifiedName()).append(";\n");
        }
        if (node instanceof TypeDeclaration) {
            sb.append(getSource(fragment, node));
        } else {
            sb.append("class ").append(enclosingClass).append(" {\n");
            sb.append(getSource(fragment, node));
            sb.append("}\n");
        }
        return sb.toString();
    }

    private String getSource(final Fragment fragment, final BodyDeclaration node) {
        return separatesComments ? getSourceWithoutComments(fragment, node) : fragment.getWiderContent();
    }

    private String getSourceWithoutComments(final Fragment fragment, final BodyDeclaration node) {
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

    private String getCommentBody(final Comment c) {
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

    // --- fragment helpers ---

    private Fragment getFragment(final int start, final int end) {
        return text.getFragment(start, end);
    }

    private Fragment getFragment(final ASTNode node) {
        return getFragment(node.getStartPosition(), node.getStartPosition() + node.getLength());
    }

    private Fragment getFragment(final ASTNode startNode, final ASTNode endNode) {
        return getFragment(startNode.getStartPosition(), endNode.getStartPosition() + endNode.getLength());
    }

    private Fragment getFragmentWithSurroundingComments(final BodyDeclaration node) {
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

    @SuppressWarnings("unused")
    private Fragment getFragmentWithoutJavadoc(final BodyDeclaration node) {
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

    private List<ASTNode> findChildNodes(final ASTNode node) {
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

    // --- naming material ---

    /**
     * The naming material of a method: its name, its escaped type parameters (or null when it has
     * none), and its escaped parameter types. Unqualifying and digesting are the naming strategy's job.
     */
    private Signature methodSignature(final MethodDeclaration node) {
        @SuppressWarnings("unchecked")
        final List<Object> types = node.typeParameters();
        final List<String> typeParameters = types == null || types.isEmpty() ? null
                : types.stream().map(o -> escape(o.toString())).toList();
        @SuppressWarnings("unchecked")
        final List<Object> params = node.parameters();
        final List<String> parameters = params.stream()
                .map(o -> typeName((SingleVariableDeclaration) o)).toList();
        return new Signature(node.getName().getIdentifier(), typeParameters, parameters);
    }

    private String typeName(final SingleVariableDeclaration v) {
        final StringBuilder sb = new StringBuilder();
        sb.append(escape(v.getType().toString()));
        sb.append("[]".repeat(Math.max(0, v.getExtraDimensions())));
        if (v.isVarargs()) {
            sb.append("...");
        }
        return sb.toString();
    }

    private String escape(final String s) {
        return s.replace(' ', '-').replace('?', '#').replace('<', '[').replace('>', ']');
    }

    /**
     * The comments attached to each declaration, resolved lazily by leading/trailing index.
     */
    private static final class CommentSet {
        private final CompilationUnit unit;
        private final List<Comment> comments;
        private final List<Integer> offsets;
        private final Map<ASTNode, List<Comment>> cache = new HashMap<>();

        CommentSet(final CompilationUnit unit) {
            this.unit = unit;
            @SuppressWarnings("unchecked")
            final List<Comment> comments = unit != null ? unit.getCommentList() : Collections.emptyList();
            this.comments = comments;
            this.offsets = comments.stream().map(ASTNode::getStartPosition).collect(Collectors.toList());
        }

        List<Comment> getComments(final ASTNode node) {
            return cache.computeIfAbsent(node, this::extractComments);
        }

        private List<Comment> extractComments(final ASTNode node) {
            final int leading = unit.firstLeadingCommentIndex(node);
            final int start = leading != -1 ? leading : lookup(node.getStartPosition());
            final int trailing = unit.lastTrailingCommentIndex(node);
            final int end = trailing != -1 ? trailing + 1 : lookup(node.getStartPosition() + node.getLength());
            return comments.subList(start, end); // [start, end)
        }

        private int lookup(final int offset) {
            final int index = Collections.binarySearch(offsets, offset);
            return index >= 0 ? index : ~index;
        }
    }
}
