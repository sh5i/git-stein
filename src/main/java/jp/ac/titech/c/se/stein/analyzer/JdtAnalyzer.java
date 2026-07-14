package jp.ac.titech.c.se.stein.analyzer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.*;
import org.eclipse.jdt.core.formatter.DefaultCodeFormatterConstants;

import jp.ac.titech.c.se.stein.analyzer.util.FormatUtils;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.core.SourceText.Fragment;
import lombok.Getter;
import lombok.Setter;
import picocli.CommandLine.Option;

/**
 * The Eclipse JDT analyzer, for Java. It parses each file with JDT's error-recovering parser and
 * wraps the compilation unit in a {@link Model}, so a broken or mid-refactor file still yields
 * whatever declarations JDT can recover.
 */
public class JdtAnalyzer implements Analyzer {
    /**
     * Whether a model renders each module in a form that is more likely to parse on its own.
     */
    @Getter
    @Setter
    @Option(names = "--jdt-parsable", description = "generate more parsable files")
    private boolean parsable = false;

    @Override
    public boolean accepts(final String filename) {
        return Language.of(filename) == Language.JAVA;
    }

    @Override
    public Language languageOf(final String filename) {
        return Language.JAVA;
    }

    @Override
    public SourceModel analyze(final String filename, final byte[] blob, final Context c) {
        final SourceText text = SourceText.ofNormalized(blob);
        return new Model(filename, text, parse(text), parsable);
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
        return (CompilationUnit) parser.createAST(null);
    }

    /**
     * A {@link SourceModel} backed by Eclipse JDT, for Java. It parses the file to a JDT AST and
     * extracts an {@link Element} tree of classes, methods, and fields with FinerGit-compatible names
     * (using JDT's resolved bindings to optionally digest parameter types or unqualify type names), and
     * renders each element's source (optionally excluding its comments or wrapping a member so it parses
     * standalone). Comment text and line numbers, which a Historage consumer needs for comment and mapping
     * side files, are exposed beyond the {@link SourceModel} contract.
     */
    public static class Model implements SourceModel {
        private final SourceText text;

        private final CompilationUnit unit;

        private final Element root;

        private final Map<Element, BodyDeclaration> nodes = new HashMap<>();

        private final Map<Element, String> enclosingClass = new HashMap<>();

        private final boolean parsable;

        Model(final String filename, final SourceText text, final CompilationUnit unit,
                 final boolean parsable) {
            this.text = text;
            this.unit = unit;
            this.parsable = parsable;
            this.root = new Element(Element.Kind.FILE, filename.substring(0, filename.lastIndexOf('.')));
            unit.accept(new Builder());
        }

        @Override
        public Element getRoot() {
            return root;
        }

        @Override
        public List<Fragment> getExtentComments(final Element e) {
            final Fragment extent = e.getExtentFragment();
            if (extent == null) {
                return List.of();
            }
            final List<Fragment> out = new ArrayList<>();
            for (final Object comment : unit.getCommentList()) {
                final Fragment c = getFragment((ASTNode) comment);
                if (c.getBegin() >= extent.getBegin() && c.getBegin() < extent.getEnd()) {
                    out.add(c);
                }
            }
            return out;
        }

        /**
         * The element's body (its extent with {@code dropped} removed); with {@code --jdt-parsable} it is
         * wrapped in its package declaration and, for a member, its enclosing class, so it parses on its own.
         */
        @Override
        public String getModuleText(final Element e, final Collection<Fragment> dropped) {
            final BodyDeclaration node = nodes.get(e);
            final String body = FormatUtils.stripComments(e.getExtentFragment(), dropped);
            if (!parsable) {
                return body;
            }
            final StringBuilder sb = new StringBuilder();
            final PackageDeclaration pkg = unit.getPackage();
            if (pkg != null) {
                sb.append("package ").append(pkg.getName().getFullyQualifiedName()).append(";\n");
            }
            if (node instanceof TypeDeclaration) {
                sb.append(body);
            } else {
                sb.append("class ").append(enclosingClass.get(e)).append(" {\n");
                sb.append(body);
                sb.append("}\n");
            }
            return sb.toString();
        }

        private final class Builder extends ASTVisitor {
            private final Deque<Element> stack = new ArrayDeque<>(List.of(root));

            private Element add(final Element.Kind kind, final Signature signature, final BodyDeclaration node) {
                final Fragment f = getFragmentWithSurroundingComments(node);
                final Element e = new Element(kind, signature);
                e.setCoreFragment(getFragment(node));
                e.setExtentFragment(f);
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
    }
}
