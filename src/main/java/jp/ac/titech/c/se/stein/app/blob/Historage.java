package jp.ac.titech.c.se.stein.app.blob;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import jp.ac.titech.c.se.stein.analyzer.Analyzer;
import jp.ac.titech.c.se.stein.analyzer.CtagsAnalyzer;
import jp.ac.titech.c.se.stein.analyzer.JdtAnalyzer;
import jp.ac.titech.c.se.stein.analyzer.SrcmlAnalyzer;
import jp.ac.titech.c.se.stein.analyzer.TreeSitterAnalyzer;
import jp.ac.titech.c.se.stein.analyzer.Element;
import jp.ac.titech.c.se.stein.analyzer.Signature;
import jp.ac.titech.c.se.stein.analyzer.SourceModel;
import jp.ac.titech.c.se.stein.analyzer.Token;
import jp.ac.titech.c.se.stein.analyzer.TokenizingModel;
import jp.ac.titech.c.se.stein.analyzer.util.FormatUtils;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.SourceText.Fragment;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import jp.ac.titech.c.se.stein.util.HashUtils;
import lombok.ToString;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Explodes each source file into finer-grained Historage modules: one file per class, method, or
 * field, named after the declaration it holds. Committing these derived files, instead of or beside
 * the originals, lets Git track history, blame, and renames at the granularity of individual program
 * entities rather than whole files. Each blob is parsed by a pluggable analysis backend and its
 * declarations are emitted as separate module files, with optional comment and mapping side files.
 *
 * @see <a href="https://github.com/hideakihata/git2historage">git2historage</a>
 * @see <a href="https://github.com/kusumotolab/FinerGit">FinerGit</a>
 */
@ToString
@Command(name = "@historage", description = "Generate finer-grained modules")
public class Historage implements BlobTranslator {
    public enum BackendType { ts, jdt, srcml, ctags }

    /**
     * What becomes of a comment: {@code keep} it in the module, {@code strip} it entirely, {@code file}
     * it into the comment side file (removed from the module), or {@code mirror} it into the side file
     * while keeping it in the module.
     */
    public enum CommentMode { keep, strip, file, mirror }

    /**
     * Whether a declaration's leading and trailing doc comments are {@code include}d in its module (then
     * governed by {@code --comment} like any comment) or {@code exclude}d from every module.
     */
    public enum DocCommentMode { include, exclude }

    /**
     * Whether to keep the original, unsplit file alongside the generated modules.
     */
    @Option(names = "--no-original", negatable = true, description = "Exclude original files")
    protected boolean requiresOriginals = true;

    /**
     * The analysis backends to try, in priority order; each file is handled by the first whose languages
     * include it.
     * <ul>
     * <li>{@code ts}: tree-sitter, every supported language.</li>
     * <li>{@code jdt}: Eclipse JDT, Java only, with binding-resolved method names.</li>
     * <li>{@code srcml}: srcML.</li>
     * <li>{@code ctags}: universal-ctags.</li>
     * </ul>
     * The default {@code ts,ctags} falls back to ctags for the languages tree-sitter does not cover.
     */
    @Option(names = "--backend", split = ",", paramLabel = "<b>",
            description = "analysis backends in priority order (${COMPLETION-CANDIDATES}; default: ${DEFAULT-VALUE})")
    protected List<BackendType> backendNames = List.of(BackendType.ts, BackendType.ctags);

    /**
     * Whether a class (or other type declaration) becomes its own module file. With {@code --tokens} a
     * class is only a naming scope and emits no file of its own, regardless of this option.
     */
    @Option(names = "--no-classes", negatable = true, description = "[ex]/include class files")
    protected boolean requiresClasses = true;

    /**
     * Whether a method becomes its own module file.
     */
    @Option(names = "--no-methods", negatable = true, description = "[ex]/include method files")
    protected boolean requiresMethods = true;

    /**
     * Whether a field becomes its own module file.
     */
    @Option(names = "--no-fields", negatable = true, description = "[ex]/include field files")
    protected boolean requiresFields = true;

    /**
     * Whether to render each module as a FinerGit-style token sequence, one token per line, in place of
     * its raw source. Applies only to the tokenizing backends (tree-sitter and srcML); {@code --tokens}
     * disables the others.
     */
    @Option(names = "--tokens", description = "render modules as FinerGit token sequences (ts, srcml)")
    protected boolean tokens = false;

    /**
     * With {@code --tokens}, whether each token is annotated with its type (FinerGit Heuristic 1).
     */
    @Option(names = "--token-type", negatable = true,
            description = "annotate each token with its type (FinerGit Heuristic 1; with --tokens)")
    protected boolean includesTokenType = true;

    /**
     * With {@code --tokens}, whether a method's frame tokens, its parameter parentheses and body braces,
     * are dropped from the sequence (FinerGit Heuristic 2).
     */
    @Option(names = "--omit-frame", negatable = true,
            description = "omit each method's parameter parentheses and body braces (Heuristic 2; with --tokens)")
    protected boolean omitsFrame = true;

    /**
     * What to do with each comment in a module: keep, strip, file (to the comment side file), or mirror
     * (to the side file and keep). Applies to tree-sitter, jdt, and srcML.
     */
    @Option(names = "--comment", paramLabel = "<mode>",
            description = "comment disposition: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})")
    protected CommentMode commentMode = CommentMode.keep;

    /**
     * Whether a declaration's leading and trailing doc comments are included in its module (then governed
     * by {@code --comment}) or excluded from every module. Applies to tree-sitter, jdt, and srcML.
     */
    @Option(names = "--doc-comment", paramLabel = "<mode>",
            description = "doc comment disposition: ${COMPLETION-CANDIDATES} (default: ${DEFAULT-VALUE})")
    protected DocCommentMode docCommentMode = DocCommentMode.include;

    /**
     * Whether to emit a mapping side file: one JSON record per module giving its file name and its line
     * range in the original source.
     */
    @Option(names = "--mapping", description = "extract mapping file")
    protected boolean requiresMapping = false;

    /**
     * The file-name extension appended to a module's name to form its comment side file.
     */
    @Option(names = "--comment-ext", paramLabel = "<ext>", description = "comment file extension (default: ${DEFAULT-VALUE})")
    protected String commentExtension = ".com";

    /**
     * The file name of the mapping side file, formed from the source file's base name and this extension.
     */
    @Option(names = "--mapping-ext", paramLabel = "<ext>", description = "mapping file extension (default: ${DEFAULT-VALUE})")
    protected String mappingExtension = ".mapping";

    /**
     * Whether a method module's name digests its parameter list into a short fixed-length hash instead
     * of spelling the parameters out, keeping the file name short.
     */
    @Option(names = "--digest-params", description = "digest parameters in module names")
    protected boolean digestParameters = false;

    /**
     * Whether the type names in a method module's name drop their qualification.
     */
    @Option(names = "--unqualify", description = "unqualify typenames in module names")
    protected boolean unqualifyTypename = false;

    /**
     * The format of a module's file extension. {@code %k} is the short kind (the letter {@code c},
     * {@code m}, or {@code f}; a RAW element, which has no short kind, falls back to its long kind),
     * {@code %K} is the long kind (the analyzer's raw kind when it has one, e.g. a ctags
     * {@code function}, otherwise {@code class}/{@code method}/{@code field}), and {@code %e} is the
     * source file's extension without its dot (empty for an extensionless file).
     */
    @Option(names = "--ext-format", paramLabel = "<fmt>",
            description = "module extension format: %%k=short kind (raw kind when unclassified),"
                    + " %%K=long kind, %%e=source extension (default: ${DEFAULT-VALUE})")
    protected String extensionFormat = NamingStrategy.DEFAULT_EXTENSION_FORMAT;

    @Mixin
    final SrcmlAnalyzer srcmlAnalyzer = new SrcmlAnalyzer();

    // no options of its own, so a plain field rather than a mixin
    private final TreeSitterAnalyzer tsAnalyzer = new TreeSitterAnalyzer();

    @Mixin
    private final JdtAnalyzer jdtAnalyzer = new JdtAnalyzer();

    @Mixin
    private final CtagsAnalyzer ctagsAnalyzer = new CtagsAnalyzer();

    @Mixin
    private final NameFilter filter = new NameFilter();

    private static final Gson GSON = new Gson();

    /**
     * The maximum length of a single file name on common file systems.
     */
    private static final int MAX_FILENAME_BYTES = 255;

    private List<Analyzer> analyzers;

    /**
     * Selects the backends to try, in priority order (mainly for programmatic use); returns this.
     */
    public Historage backends(final BackendType... names) {
        this.backendNames = List.of(names);
        this.analyzers = null;
        return this;
    }

    @Override
    public void setUp(final Context c) {
        analyzers(); // build and validate the backend selection at startup
    }

    @Override
    public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
        for (final Analyzer analyzer : analyzers()) {
            if (handles(analyzer, entry.getName())) {
                final AnyHotEntry.Set result = AnyHotEntry.set();
                if (requiresOriginals) {
                    result.add(entry);
                }
                for (final HotEntry module : generateModules(analyzer, entry, c)) {
                    result.add(module);
                }
                return result;
            }
        }
        return entry;
    }

    /**
     * The chosen analyzers, built lazily once the options are parsed. With {@code --tokens} the
     * non-tokenizing analyzers (those that render raw source only) are dropped, and it is an error if
     * none remains.
     */
    private List<Analyzer> analyzers() {
        if (analyzers == null) {
            List<Analyzer> selected = backendNames.stream().map(this::analyzer).toList();
            if (tokens) {
                selected = selected.stream().filter(a -> a instanceof Analyzer.Tokenizing).toList();
                if (selected.isEmpty()) {
                    throw new IllegalArgumentException(
                            "--tokens needs a tokenizing backend (ts or srcml); none of " + backendNames + " qualifies");
                }
            }
            analyzers = selected;
        }
        return analyzers;
    }

    private Analyzer analyzer(final BackendType backend) {
        return switch (backend) {
            case ts -> tsAnalyzer;
            case jdt -> jdtAnalyzer;
            case srcml -> srcmlAnalyzer;
            case ctags -> ctagsAnalyzer;
        };
    }

    /**
     * Whether the analyzer handles the file. The ctags analyzer accepts any file, so the app's name
     * filter narrows it; the other analyzers know their own languages.
     */
    private boolean handles(final Analyzer analyzer, final String filename) {
        if (analyzer == ctagsAnalyzer) {
            return analyzer.accepts(filename) && filter.accept(filename);
        }
        return analyzer.accepts(filename);
    }

    private List<? extends HotEntry> generateModules(final Analyzer analyzer, final BlobEntry entry, final Context c) {
        final SourceModel source = analyzer.analyze(entry.getName(), entry.getBlob(), c);
        if (source == null) {
            return List.of();
        }
        final Element root = source.getRoot();
        final NamingStrategy naming = new NamingStrategy(unqualifyTypename, digestParameters, extensionFormat);

        // collect the elements that become modules, then assign each a file name, appending @2, @3,
        // ... to the second and later occurrences of the same name
        final List<Generated> modules = new ArrayList<>();
        collect(source, root, naming, entry.getName(), modules);
        final Map<String, Integer> counter = new HashMap<>();
        final List<String> filenames = new ArrayList<>();
        final List<HotEntry> out = new ArrayList<>();
        for (final Generated m : modules) {
            final int index = counter.merge(m.filename(1), 1, Integer::sum);
            final String name = m.filename(index);
            filenames.add(name);
            out.add(HotEntry.of(entry.getMode(), name, m.content.getBytes(StandardCharsets.UTF_8)));
        }
        for (int i = 0; i < modules.size(); i++) {
            final List<Fragment> filed = modules.get(i).filed();
            if (!filed.isEmpty()) {
                out.add(HotEntry.of(entry.getMode(), filenames.get(i) + commentExtension,
                        renderComments(filed).getBytes(StandardCharsets.UTF_8)));
            }
        }
        if (requiresMapping && !modules.isEmpty()) {
            out.add(HotEntry.of(entry.getMode(), root.getName() + mappingExtension,
                    mappingContent(modules, filenames).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private void collect(final SourceModel source, final Element parent, final NamingStrategy naming,
                         final String filename, final List<Generated> out) {
        for (final Element e : parent.getChildren()) {
            if (e.hasContent() && wants(e.getKind())) {
                final String basename = naming.basename(e);
                final String extension = naming.extension(e.getKind(), e.getRawKind(), filename);
                final Disposition d = disposition(source, e);
                final String content = tokens ? renderTokenSequence((TokenizingModel) source, e, d.dropped())
                        : source.getModuleText(e, d.dropped());
                out.add(new Generated(e, content, basename, extension, d.filed()));
            }
            collect(source, e, naming, filename, out);
        }
    }

    /**
     * Splits an element's comments into the ones dropped from its module body and the ones filed to its
     * comment side file, per {@code --comment} and {@code --doc-comment}. An excluded doc comment is
     * dropped and not filed. When the backend has no notion of comments, both are empty.
     */
    private Disposition disposition(final SourceModel source, final Element e) {
        final List<Fragment> dropped = new ArrayList<>();
        final List<Fragment> filed = new ArrayList<>();
        final List<Fragment> bodies = source.getBodyComments(e);
        if (bodies != null) {
            bodies.forEach(c -> route(commentMode, c, dropped, filed));
        }
        final List<Fragment> docs = source.getDocComments(e);
        if (docs != null) {
            for (final Fragment c : docs) {
                if (docCommentMode == DocCommentMode.exclude) {
                    dropped.add(c);  // gone: out of the module and not filed
                } else {
                    route(commentMode, c, dropped, filed);
                }
            }
        }
        return new Disposition(dropped, filed);
    }

    /**
     * Routes one comment to the {@code dropped} and/or {@code filed} lists per the comment mode.
     */
    private static void route(final CommentMode mode, final Fragment c,
                              final List<Fragment> dropped, final List<Fragment> filed) {
        switch (mode) {
            case keep -> { }                                // in the module, not filed
            case strip -> dropped.add(c);                   // out of the module, not filed
            case file -> { dropped.add(c); filed.add(c); }  // out of the module, filed
            case mirror -> filed.add(c);                    // in the module, filed
        }
    }

    /**
     * An element's comments split by {@code --comment}/{@code --doc-comment}: {@code dropped} from the
     * module body, {@code filed} to the comment side file.
     */
    private record Disposition(List<Fragment> dropped, List<Fragment> filed) {
    }

    /**
     * The comment side file body: each comment de-indented, in source order.
     */
    private static String renderComments(final List<Fragment> comments) {
        final StringBuilder sb = new StringBuilder();
        comments.stream()
                .sorted(Comparator.comparingInt(Fragment::getBegin))
                .forEach(c -> sb.append(FormatUtils.dedent(c.getWiderContent())));
        return sb.toString();
    }

    /**
     * The FinerGit token sequence of an element: each leaf token on its own line, dropping the comment
     * tokens the disposition removes from the module and (with {@code --omit-frame}) frame tokens, and
     * annotating each with its type when {@code --token-type} is set. Assembled here from the analyzer's
     * neutral token stream, so the FinerGit rendering policy stays with this consumer.
     */
    private String renderTokenSequence(final TokenizingModel analyzer, final Element e,
                                       final Collection<Fragment> dropped) {
        final StringBuilder sb = new StringBuilder();
        for (final Token t : analyzer.getTokens(e)) {
            if (t.comment() && isInRange(dropped, t.start())) {
                continue;  // a comment token the disposition drops from the module
            }
            if (omitsFrame && t.frame()) {
                continue;
            }
            sb.append(t.text());
            if (includesTokenType) {
                sb.append(' ').append(t.type());
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * Whether the position falls within any of the comment fragments.
     */
    private static boolean isInRange(final Collection<Fragment> comments, final int pos) {
        for (final Fragment c : comments) {
            if (pos >= c.getBegin() && pos < c.getEnd()) {
                return true;
            }
        }
        return false;
    }

    private boolean wants(final Element.Kind kind) {
        return switch (kind) {
            case CLASS -> requiresClasses && !tokens;  // in token mode classes are naming scopes only
            case METHOD -> requiresMethods;
            case FIELD -> requiresFields;
            case RAW -> true;  // outside the neutral kinds; narrowed by the analyzer (--ctags-kind)
            case FILE -> false;
        };
    }

    private String mappingContent(final List<Generated> modules, final List<String> filenames) {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < modules.size(); i++) {
            final Element e = modules.get(i).element;
            final JsonObject o = new JsonObject();
            o.addProperty("filename", filenames.get(i));
            o.addProperty("beginLine", e.getStartLine());
            o.addProperty("endLine", e.getEndLine());
            sb.append(GSON.toJson(o)).append("\n");
        }
        return sb.toString();
    }

    /**
     * A generated module before its final file name: the element it came from, its rendered content,
     * and the base name and extension a naming strategy produced. {@link #filename} assembles the file
     * name, inserting a {@code @index} conflict marker (when 2 or more) and truncating an over-long base.
     */
    private record Generated(Element element, String content, String basename, String extension,
                             List<Fragment> filed) {
        String filename(final int index) {
            final String suffix = (index >= 2 ? "@" + index : "") + extension;
            final int budget = MAX_FILENAME_BYTES - suffix.getBytes(StandardCharsets.UTF_8).length;
            return HashUtils.abbreviateToBytes(basename, budget) + suffix;
        }
    }

    /**
     * Turns an extracted {@link Element} into the base file name (without extension or conflict index)
     * of its Historage module, by walking the element up its parent chain. It also formats each
     * element's leaf name from the {@link Signature} the analyzer emitted and chooses the module's file
     * extension. The concrete conventions are the nested {@link FinerGit}, {@link Scoped}, and
     * {@link Ctags}.
     */
    /**
     * The naming convention for Historage modules, shared by every backend. A module's base name is
     * the path from the file root joined with separators that encode each edge: {@code !} separates
     * the file base from a top-level declaration and is elided when the declaration's name equals the
     * file base (FinerGit's Java convention, generalized), {@code .} nests a type in a type, and
     * {@code #} attaches anything else (a member, or a raw-kind tag) to its scope. The extension is
     * expanded from a {@code %x}-style format (see {@link #extension}); its default {@code .%k%e} is a
     * kind marker followed by the source extension, {@code c}/{@code m}/{@code f} for the neutral
     * kinds and the analyzer-specific raw kind for a {@link Element.Kind#RAW} element.
     */
    static class NamingStrategy {
        static final String DEFAULT_EXTENSION_FORMAT = ".%k%e";

        private final boolean unqualifyTypename;

        private final boolean digestParameters;

        private final String extensionFormat;

        NamingStrategy(final boolean unqualifyTypename, final boolean digestParameters, final String extensionFormat) {
            this.unqualifyTypename = unqualifyTypename;
            this.digestParameters = digestParameters;
            this.extensionFormat = extensionFormat;
        }

        String basename(final Element e) {
            final String leaf = leafName(e.getSignature());
            if (e.getKind() == Element.Kind.FILE) {
                return leaf;
            }
            final Element parent = e.getParent();
            if (parent.getKind() == Element.Kind.FILE) {
                final String base = basename(parent);
                return base.equals(leaf) ? leaf : base + "!" + leaf;
            }
            return basename(parent) + (e.getKind() == Element.Kind.CLASS ? "." : "#") + leaf;
        }

        /**
         * Assembles an element's leaf name from the raw {@link Signature} the analyzer extracted:
         * prefixes the type parameters as {@code [..]_}, appends the name, and wraps the parameter list
         * in parentheses when the signature has one (an empty list yields {@code ()}). The analyzer
         * supplies each part already escaped for file names; since the assembled punctuation
         * ({@code []()_,}) is not itself a reserved character, no further escaping is needed here.
         */
        String leafName(final Signature signature) {
            final StringBuilder sb = new StringBuilder();
            if (signature.typeParameters() != null && !signature.typeParameters().isEmpty()) {
                sb.append("[").append(String.join(",", signature.typeParameters())).append("]_");
            }
            sb.append(signature.name());
            if (signature.parameters() != null) {
                sb.append("(").append(formatParameters(String.join(",", signature.parameters()))).append(")");
            }
            return sb.toString();
        }

        /**
         * Transforms the comma-joined parameter list before it is wrapped in parentheses: optionally
         * unqualifies type names, and optionally digests the whole list into a short hash.
         */
        String formatParameters(final String parameters) {
            String result = parameters;
            if (unqualifyTypename) {
                result = result.replaceAll("[a-zA-Z0-9_$]+\\.", "");
            }
            if (digestParameters && !result.isEmpty()) {
                result = "~" + HashUtils.digest(result, 6);
            }
            return result;
        }

        /**
         * The module file extension, expanded from the extension format: {@code %k} is the short kind
         * (the letter {@code c}/{@code m}/{@code f}; a RAW element, which has no short kind, falls back
         * to its long kind), {@code %K} the long kind (the analyzer's raw kind when it has one,
         * otherwise the neutral kind's name), {@code %e} the source file's extension without its dot,
         * and {@code %%} a literal percent. Anything else is kept as is.
         */
        String extension(final Element.Kind kind, final String rawKind, final String filename) {
            final String longKind = rawKind != null ? rawKind : kind.name().toLowerCase(Locale.ROOT);
            final String shortKind = switch (kind) {
                case CLASS -> "c";
                case METHOD -> "m";
                case FIELD -> "f";
                case RAW -> longKind;
                case FILE -> "";
            };
            final int index = filename.lastIndexOf('.');
            final String ext = index > 0 ? filename.substring(index + 1) : "";
            if (extensionFormat.equals(DEFAULT_EXTENSION_FORMAT)) {
                return "." + shortKind + ext;  // shortcut for the default format
            }
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < extensionFormat.length(); i++) {
                final char c = extensionFormat.charAt(i);
                if (c != '%' || i + 1 == extensionFormat.length()) {
                    sb.append(c);
                    continue;
                }
                final char specifier = extensionFormat.charAt(++i);
                switch (specifier) {
                    case 'k' -> sb.append(shortKind);
                    case 'K' -> sb.append(longKind);
                    case 'e' -> sb.append(ext);
                    case '%' -> sb.append('%');
                    default -> sb.append('%').append(specifier);
                }
            }
            return sb.toString();
        }
    }
}
