package jp.ac.titech.c.se.stein.app.blob;

import java.util.List;
import java.util.Locale;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.historage.Module;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterJava;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * A FinerGit-style generator using tree-sitter, currently for Java. It reuses the extraction and
 * FinerGit-compatible naming of {@link HistorageTreeSitter.JavaModuleGenerator} (one file per method
 * and field), overriding only the content: instead of the raw source, each file holds the FinerGit
 * token sequence, one token per line annotated with a type (Heuristic 1). The token type of a
 * bracket, parenthesis, or semicolon is refined with its syntactic context (for example a brace
 * opening a method body versus an {@code if} block), and identifiers are refined into FinerGit-style
 * roles, so that git's line-based tracking rarely matches unrelated tokens.
 *
 * @see <a href="https://github.com/kusumotolab/FinerGit">FinerGit</a>
 */
@Slf4j
@ToString
@Command(name = "@finer", description = "Generate FinerGit-style token-sequence Java method/field files via tree-sitter")
public class Finer extends HistorageBase {
    public static final NameFilter JAVA = new NameFilter(true, "*.java");

    @Option(names = "--no-methods", negatable = true, description = "[ex]/include method files")
    protected boolean requiresMethods = true;

    @Option(names = "--no-fields", negatable = true, description = "[ex]/include field files")
    protected boolean requiresFields = true;

    @Option(names = "--token-type", negatable = true,
            description = "annotate each token with its type (FinerGit Heuristic 1)")
    protected boolean includesTokenType = true;

    @Option(names = "--omit-frame", negatable = true,
            description = "omit each method's parameter parentheses and body braces (FinerGit Heuristic 2)")
    protected boolean omitsFrame = true;

    /**
     * Tree-sitter parsers are not thread-safe; one per thread.
     */
    private static final ThreadLocal<TSParser> PARSER = ThreadLocal.withInitial(() -> {
        final TSParser parser = new TSParser();
        parser.setLanguage(new TreeSitterJava());
        return parser;
    });

    @Override
    protected boolean accepts(final BlobEntry entry) {
        return JAVA.accept(entry);
    }

    @Override
    protected List<? extends HotEntry> generateModules(final BlobEntry entry, final Context c) {
        final SourceText text = SourceText.ofNormalized(entry.getBlob());
        final TSNode root = PARSER.get().parseString(null, text.getContent()).getRootNode();
        if (root.hasError()) {
            log.debug("Syntax errors found; extracting the modules that parsed");
        }
        final List<Module> modules = new Generator(entry.getName(), text).run(root);
        Module.resolveNameConflicts(modules);
        return modules.stream()
                .map(m -> HotEntry.of(entry.getMode(), m.getFilename(), m.getBlob()))
                .toList();
    }

    /**
     * Reuses the Java extraction of {@link HistorageTreeSitter.JavaModuleGenerator} (classes are
     * naming scopes only, since {@code requiresClasses} is false) and replaces the module content
     * with the FinerGit token sequence.
     */
    public class Generator extends HistorageTreeSitter.JavaModuleGenerator {
        // the declaration currently being tokenized and its frame nodes, for Heuristic 2
        private TSNode frameRoot;

        private TSNode frameParameters;

        private TSNode frameBody;

        public Generator(final String filename, final SourceText text) {
            super(filename, text, false, Finer.this.requiresMethods, Finer.this.requiresFields);
        }

        /**
         * The content of a method or field is its FinerGit token sequence rather than its raw source:
         * each leaf token on its own line followed by its type (Heuristic 1). Comments are skipped.
         * When {@code --omit-frame} is set (Heuristic 2), a method's parameter parentheses, body
         * braces, and bodyless terminating semicolon are dropped.
         */
        @Override
        protected String contentOf(final TSNode node) {
            frameRoot = node;
            frameParameters = node.getChildByFieldName("parameters");
            frameBody = node.getChildByFieldName("body");
            final StringBuilder sb = new StringBuilder();
            emitLeaves(node, sb);
            return sb.toString();
        }

        protected void emitLeaves(final TSNode node, final StringBuilder sb) {
            if (node.getChildCount() > 0) {
                for (int i = 0; i < node.getChildCount(); i++) {
                    emitLeaves(node.getChild(i), sb);
                }
                return;
            }
            if (node.isExtra() || node.isMissing()) {
                return; // a comment or an inserted-error token is not part of the token sequence
            }
            if (omitsFrame && isFrameToken(node)) {
                return;
            }
            final String token = textOf(node).replaceAll("[\\r\\n]+", " ");
            if (token.isEmpty()) {
                return;
            }
            sb.append(token);
            if (includesTokenType) {
                sb.append(" ").append(category(node));
            }
            sb.append("\n");
        }

        /**
         * Whether the leaf is one of a method's omnipresent frame tokens (FinerGit Heuristic 2): the
         * parentheses of its parameter list, the braces of its body, or the terminating semicolon of
         * a bodyless (abstract or interface) method.
         */
        protected boolean isFrameToken(final TSNode leaf) {
            final TSNode parent = leaf.getParent();
            return switch (leaf.getType()) {
                case "(", ")" -> !frameParameters.isNull() && sameNode(parent, frameParameters);
                // the body is a block for a method, or a constructor_body for a constructor
                case "{", "}" -> !frameBody.isNull() && sameNode(parent, frameBody);
                case ";" -> isMethodDeclaration(frameRoot) && sameNode(parent, frameRoot);
                default -> false;
            };
        }

        protected boolean isMethodDeclaration(final TSNode node) {
            return switch (node.getType()) {
                case "method_declaration", "constructor_declaration", "compact_constructor_declaration" -> true;
                default -> false;
            };
        }

        /**
         * The FinerGit token type. Brackets, parentheses, and semicolons are refined with their
         * syntactic context (Heuristic 1): a brace or parenthesis in a wrapper node ({@code block},
         * {@code parenthesized_expression}) takes its role from the enclosing statement, so a method
         * body brace and an {@code if} block brace get distinct types. Identifiers are refined into
         * FinerGit-style roles by {@link #identifierRole}. Other tokens (keywords, operators,
         * literals) use their tree-sitter node type.
         */
        protected String category(final TSNode leaf) {
            final String type = leaf.getType();
            final String symbol = switch (type) {
                case "(" -> "LPAREN";
                case ")" -> "RPAREN";
                case "{" -> "LBRACE";
                case "}" -> "RBRACE";
                case ";" -> "SEMICOLON";
                case "," -> "COMMA";
                case "[" -> "LBRACKET";
                case "]" -> "RBRACKET";
                default -> null;
            };
            if (symbol != null) {
                final TSNode parent = leaf.getParent();
                String context = parent.getType();
                if ((type.equals("{") || type.equals("}")) && context.equals("block")) {
                    context = parent.getParent().getType();
                } else if ((type.equals("(") || type.equals(")")) && context.equals("parenthesized_expression")) {
                    context = parent.getParent().getType();
                }
                return context.toUpperCase(Locale.ROOT) + "_" + symbol;
            }
            return switch (type) {
                case "identifier" -> identifierRole(leaf);
                case "type_identifier" ->
                        leaf.getParent().getType().equals("type_parameter") ? "TYPE_PARAMETER_NAME" : "TYPE_NAME";
                default -> type.toUpperCase(Locale.ROOT);
            };
        }

        /**
         * The role of an identifier, following FinerGit: the name declaring a type, method, or
         * constructor, or invoking a method, is typed distinctly so it does not match a variable of
         * the same text; every other identifier (variable declarations and uses, field accesses,
         * qualifiers) is a plain variable name, kept uniform so that moving one does not break
         * tracking.
         */
        protected String identifierRole(final TSNode leaf) {
            final TSNode parent = leaf.getParent();
            final TSNode name = parent.getChildByFieldName("name");
            if (!name.isNull() && sameNode(name, leaf)) {
                return switch (parent.getType()) {
                    case "method_declaration", "constructor_declaration", "compact_constructor_declaration" ->
                            "DECLARED_METHOD_NAME";
                    case "method_invocation" -> "INVOKED_METHOD_NAME";
                    case "class_declaration", "interface_declaration", "enum_declaration",
                         "annotation_type_declaration" -> "CLASS_NAME";
                    case "record_declaration" -> "RECORD_NAME";
                    default -> "VARIABLE_NAME";
                };
            }
            return "VARIABLE_NAME";
        }

        protected boolean sameNode(final TSNode a, final TSNode b) {
            return a.getStartByte() == b.getStartByte() && a.getEndByte() == b.getEndByte();
        }
    }
}
