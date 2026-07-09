package jp.ac.titech.c.se.stein.app.blob;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import jp.ac.titech.c.se.stein.analyzer.CtagsAnalyzer;
import jp.ac.titech.c.se.stein.analyzer.Element;
import jp.ac.titech.c.se.stein.analyzer.JdtAnalyzer;
import jp.ac.titech.c.se.stein.analyzer.Languages;
import jp.ac.titech.c.se.stein.analyzer.RenderOptions;
import jp.ac.titech.c.se.stein.analyzer.Signature;
import jp.ac.titech.c.se.stein.analyzer.SourceAnalyzer;
import jp.ac.titech.c.se.stein.analyzer.SrcmlAnalyzer;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import jp.ac.titech.c.se.stein.util.HashUtils;
import jp.ac.titech.c.se.stein.util.Names;
import lombok.ToString;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Splits source files into finer-grained Historage modules (one file per class, method, or field),
 * choosing the analysis backend per file. {@code --backend} lists backends in priority order; each
 * file is handled by the first that accepts it. Available backends: {@code ts} (tree-sitter, every
 * supported language), {@code jdt} (Eclipse JDT, Java only, adding comment/mapping side files and
 * binding-based method naming), {@code srcml}, and {@code ctags} (universal-ctags, needs the
 * {@code ctags} command). The default {@code ts,ctags} handles all tree-sitter languages with
 * tree-sitter and falls back to ctags for the rest; use {@code --backend jdt} for the JDT-only
 * features.
 *
 * <p>With {@code --tokens} each module's content is rendered as a FinerGit-style token sequence (one
 * token per line, optionally annotated with its type) instead of the raw source, and classes become
 * naming scopes only; this only applies where the analyzer tokenizes, i.e. the tree-sitter backend.</p>
 *
 * @see <a href="https://github.com/kusumotolab/FinerGit">FinerGit</a>
 */
@ToString
@Command(name = "@historage", description = "Generate finer-grained modules")
public class Historage implements BlobTranslator {
    public enum BackendType { ts, jdt, srcml, ctags }

    @Option(names = "--no-original", negatable = true, description = "Exclude original files")
    protected boolean requiresOriginals = true;

    @Option(names = "--backend", split = ",", paramLabel = "<b>",
            description = "analysis backends in priority order (${COMPLETION-CANDIDATES}; default: ${DEFAULT-VALUE})")
    protected List<BackendType> backendNames = List.of(BackendType.ts, BackendType.ctags);

    @Option(names = "--no-classes", negatable = true, description = "[ex]/include class files")
    protected boolean requiresClasses = true;

    @Option(names = "--no-methods", negatable = true, description = "[ex]/include method files")
    protected boolean requiresMethods = true;

    @Option(names = "--no-fields", negatable = true, description = "[ex]/include field files")
    protected boolean requiresFields = true;

    @Option(names = "--tokens", description = "render modules as FinerGit token sequences (tree-sitter)")
    protected boolean tokens = false;

    @Option(names = "--token-type", negatable = true,
            description = "annotate each token with its type (FinerGit Heuristic 1; with --tokens)")
    protected boolean includesTokenType = true;

    @Option(names = "--omit-frame", negatable = true,
            description = "omit each method's parameter parentheses and body braces (Heuristic 2; with --tokens)")
    protected boolean omitsFrame = true;

    @Option(names = "--comments", description = "extract comment files (jdt)")
    protected boolean requiresComments = false;

    @Option(names = "--separate-comments", description = "exclude comments from modules (jdt)")
    protected boolean separatesComments = false;

    @Option(names = "--mapping", description = "extract mapping file (jdt)")
    protected boolean requiresMapping = false;

    @Option(names = "--comment-ext", paramLabel = "<ext>", description = "comment file extension (default: ${DEFAULT-VALUE})")
    protected String commentExtension = ".com";

    @Option(names = "--mapping-ext", paramLabel = "<ext>", description = "mapping file extension (default: ${DEFAULT-VALUE})")
    protected String mappingExtension = ".mapping";

    @Option(names = "--digest-params", description = "digest parameters (jdt)")
    protected boolean digestParameters = false;

    @Option(names = "--unqualify", description = "unqualify typenames (jdt)")
    protected boolean unqualifyTypename = false;

    @Option(names = "--parsable", description = "generate more parsable files (jdt)")
    protected boolean parsable = false;

    @Option(names = "--srcml", description = "srcml command used (srcml)")
    protected String srcml = "srcml";

    @Option(names = "--ctags", description = "ctags command used (ctags)")
    protected String ctags = "ctags";

    @Option(names = "--no-original-ext", negatable = true, description = "disuse original file extension (ctags)")
    protected boolean requiresOriginalExtension = true;

    @Option(names = "--no-digest-sig", negatable = true, description = "stop digesting signature (ctags)")
    protected boolean digestSignature = true;

    @Option(names = "--kind", paramLabel = "<k>", description = "module kinds to include (ctags)",
            arity = "0..*", split = ",")
    protected Set<String> moduleKinds;

    @Mixin
    private final NameFilter filter = new NameFilter();

    private static final Gson GSON = new Gson();

    /**
     * The maximum length of a single file name on common file systems.
     */
    private static final int MAX_FILENAME_BYTES = 255;

    private List<Engine> engines;

    /**
     * Selects the backends to try, in priority order (mainly for programmatic use); returns this.
     */
    public Historage backends(final BackendType... names) {
        this.backendNames = List.of(names);
        this.engines = null;
        return this;
    }

    @Override
    public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
        for (final Engine engine : engines()) {
            if (engine.accepts().test(entry.getName())) {
                final AnyHotEntry.Set result = AnyHotEntry.set();
                if (requiresOriginals) {
                    result.add(entry);
                }
                for (final HotEntry module : generateModules(engine, entry, c)) {
                    result.add(module);
                }
                return result;
            }
        }
        return entry;
    }

    /**
     * How one backend analyses a file: whether it handles the blob, the analyzer over it, and (when it
     * has its own convention such as ctags) the naming strategy to use instead of the default.
     */
    private record Engine(Predicate<String> accepts, BiFunction<BlobEntry, Context, SourceAnalyzer> analyzer,
                          Function<String, NamingStrategy> naming) {
    }

    /**
     * The chosen backend engines, built lazily once the options are parsed.
     */
    private List<Engine> engines() {
        if (engines == null) {
            engines = backendNames.stream().map(this::engine).toList();
        }
        return engines;
    }

    private Engine engine(final BackendType backend) {
        return switch (backend) {
            case ts -> new Engine(Languages::accepts, (e, c) -> Languages.of(e.getName(), e.getBlob()), null);
            case jdt -> new Engine(JdtAnalyzer::accepts,
                    (e, c) -> JdtAnalyzer.of(e.getName(), e.getBlob(), separatesComments, parsable), null);
            case srcml -> new Engine(SrcmlAnalyzer::accepts,
                    (e, c) -> SrcmlAnalyzer.of(e.getName(), e.getBlob(), srcml, null, c), null);
            case ctags -> new Engine(filter::accept,
                    (e, c) -> CtagsAnalyzer.of(e.getName(), e.getBlob(), ctags, moduleKinds, requiresOriginalExtension, c),
                    f -> new NamingStrategy.Ctags(digestSignature, requiresOriginalExtension));
        };
    }

    private List<? extends HotEntry> generateModules(final Engine engine, final BlobEntry entry, final Context c) {
        final SourceAnalyzer source = engine.analyzer().apply(entry, c);
        if (source == null) {
            return List.of();
        }
        final Element root = source.extract();
        final NamingStrategy naming = engine.naming() != null ? engine.naming().apply(entry.getName())
                : entry.getName().endsWith(".java")
                ? new NamingStrategy.FinerGit(unqualifyTypename, digestParameters) : NamingStrategy.Scoped.INSTANCE;
        final RenderOptions render = tokens ? new RenderOptions(true, includesTokenType, omitsFrame) : RenderOptions.RAW;

        // collect the elements that become modules, then assign each a file name, appending @2, @3,
        // ... to the second and later occurrences of the same name
        final List<Generated> modules = new ArrayList<>();
        collect(source, root, naming, render, entry.getName(), modules);
        final Map<String, Integer> counter = new HashMap<>();
        final List<String> filenames = new ArrayList<>();
        final List<HotEntry> out = new ArrayList<>();
        for (final Generated m : modules) {
            final int index = counter.merge(m.filename(1), 1, Integer::sum);
            final String name = m.filename(index);
            filenames.add(name);
            out.add(HotEntry.of(entry.getMode(), name, m.content.getBytes(StandardCharsets.UTF_8)));
        }
        if (requiresComments) {
            for (int i = 0; i < modules.size(); i++) {
                final String comment = source.commentText(modules.get(i).element);
                if (comment != null) {
                    out.add(HotEntry.of(entry.getMode(), filenames.get(i) + commentExtension,
                            comment.getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
        if (requiresMapping && !modules.isEmpty()) {
            out.add(HotEntry.of(entry.getMode(), root.getName() + mappingExtension,
                    mappingContent(modules, filenames).getBytes(StandardCharsets.UTF_8)));
        }
        return out;
    }

    private void collect(final SourceAnalyzer source, final Element parent, final NamingStrategy naming,
                         final RenderOptions render, final String filename, final List<Generated> out) {
        for (final Element e : parent.getChildren()) {
            if (e.hasContent() && wants(e.getKind())) {
                final String basename = naming.basename(e);
                final String extension = naming.extension(e.getKind(), e.getRawKind(), filename);
                out.add(new Generated(e, source.render(e, render), basename, extension));
            }
            collect(source, e, naming, render, filename, out);
        }
    }

    private boolean wants(final Element.Kind kind) {
        return switch (kind) {
            case CLASS -> requiresClasses && !tokens;  // in token mode classes are naming scopes only
            case METHOD -> requiresMethods;
            case FIELD -> requiresFields;
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
    private record Generated(Element element, String content, String basename, String extension) {
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
    interface NamingStrategy {
        String basename(Element element);

        /**
         * Assembles an element's leaf name from the raw {@link Signature} the analyzer extracted:
         * prefixes the type parameters as {@code [..]_}, appends the name, and wraps the parameter list
         * in parentheses when the signature has one (an empty list yields {@code ()}). The analyzer
         * supplies each part already escaped for file names; since the assembled punctuation
         * ({@code []()_,}) is not itself a reserved character, no further escaping is needed here. A
         * strategy that transforms the parameters (FinerGit's digesting or unqualifying) overrides
         * {@link #formatParameters}.
         */
        default String leafName(final Signature signature) {
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
         * Transforms the comma-joined parameter list before it is wrapped in parentheses. The default
         * keeps it unchanged; FinerGit overrides it to optionally unqualify type names and digest it.
         */
        default String formatParameters(final String parameters) {
            return parameters;
        }

        /**
         * The module file extension for an element's kind, its analyzer-specific raw kind (or null), and
         * the source file name. The default marks a class, method, or field with a single letter before
         * the source extension (e.g. {@code .mjava}); a strategy for a tag-based analyzer may use the
         * raw kind.
         */
        default String extension(final Element.Kind kind, final String rawKind, final String filename) {
            final String letter = switch (kind) {
                case CLASS -> "c";
                case METHOD -> "m";
                case FIELD -> "f";
                case FILE -> "";
            };
            return "." + letter + filename.substring(filename.lastIndexOf('.') + 1);
        }

        /**
         * The FinerGit naming convention used by the Java Historage generators. Nested classes join with
         * {@code .} and members with {@code #}; a top-level class whose name differs from the file base
         * is written as {@code Name[FileBase]}. As a naming policy it also transforms a method's
         * parameter list: optionally unqualifying type names and digesting the whole list into a hash.
         */
        class FinerGit implements NamingStrategy {
            private final boolean unqualifyTypename;

            private final boolean digestParameters;

            public FinerGit(final boolean unqualifyTypename, final boolean digestParameters) {
                this.unqualifyTypename = unqualifyTypename;
                this.digestParameters = digestParameters;
            }

            @Override
            public String basename(final Element e) {
                final String leaf = leafName(e.getSignature());
                return switch (e.getKind()) {
                    case FILE -> leaf;
                    case CLASS -> e.getParent().getKind() == Element.Kind.CLASS
                            ? basename(e.getParent()) + "." + leaf
                            : basename(e.getParent()).equals(leaf) ? leaf : leaf + "[" + basename(e.getParent()) + "]";
                    case METHOD, FIELD -> basename(e.getParent()) + "#" + leaf;
                };
            }

            @Override
            public String formatParameters(final String parameters) {
                String result = parameters;
                if (unqualifyTypename) {
                    result = result.replaceAll("[a-zA-Z0-9_$]+\\.", "");
                }
                if (digestParameters && !result.isEmpty()) {
                    result = "~" + HashUtils.digest(result, 6);
                }
                return result;
            }
        }

        /**
         * The scoped naming convention shared by languages with explicit namespaces or packages:
         * namespaces and classes nest with {@code .} and members with {@code #}, and a top-level
         * definition is separated from the file base with {@code !}. It stays portable across file
         * systems by using no reserved characters. Used by the Python, C++, and C# generators.
         */
        class Scoped implements NamingStrategy {
            public static final Scoped INSTANCE = new Scoped();

            @Override
            public String basename(final Element e) {
                final String leaf = leafName(e.getSignature());
                return switch (e.getKind()) {
                    case FILE -> leaf;
                    case CLASS -> basename(e.getParent()) + (e.getParent().getKind() == Element.Kind.FILE ? "!" : ".") + leaf;
                    case METHOD, FIELD -> basename(e.getParent()) + (e.getParent().getKind() == Element.Kind.FILE ? "!" : "#") + leaf;
                };
            }
        }

        /**
         * The naming convention for the ctags-based Historage generator: a leaf's file name is
         * {@code <fileBase>!<scope>$<name>(<signature>).<ctagsKind>}, where the enclosing scope (which
         * ctags reports as a dotted string) is a single element under the file root. The signature is
         * normalized and either digested to a short hash or made file-name-safe; the extension is the
         * ctags kind, optionally followed by the source file's own extension.
         */
        class Ctags implements NamingStrategy {
            private final boolean digestSignature;

            private final boolean requiresOriginalExtension;

            public Ctags(final boolean digestSignature, final boolean requiresOriginalExtension) {
                this.digestSignature = digestSignature;
                this.requiresOriginalExtension = requiresOriginalExtension;
            }

            @Override
            public String basename(final Element e) {
                final String leaf = leafName(e.getSignature());
                if (e.getKind() == Element.Kind.FILE) {
                    return leaf;
                }
                return e.getParent().getKind() == Element.Kind.FILE
                        ? basename(e.getParent()) + "!" + leaf
                        : basename(e.getParent()) + "$" + leaf;
            }

            @Override
            public String formatParameters(final String parameters) {
                final String signature = parameters.replaceAll(" ?([,;:]) ?", "$1");
                return digestSignature ? "~" + HashUtils.digest(signature, 6) : Names.escape(signature);
            }

            @Override
            public String extension(final Element.Kind kind, final String rawKind, final String filename) {
                if (requiresOriginalExtension) {
                    final int index = filename.lastIndexOf('.');
                    return "." + rawKind + (index > 0 ? filename.substring(index) : "");
                }
                return "." + rawKind;
            }
        }
    }
}
