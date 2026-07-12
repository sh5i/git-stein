package jp.ac.titech.c.se.stein.app.blob;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import jp.ac.titech.c.se.stein.analyzer.Analyzer;
import jp.ac.titech.c.se.stein.analyzer.SrcmlAnalyzer;
import jp.ac.titech.c.se.stein.analyzer.TreeSitterAnalyzer;
import jp.ac.titech.c.se.stein.analyzer.Element;
import jp.ac.titech.c.se.stein.analyzer.Token;
import jp.ac.titech.c.se.stein.analyzer.TokenizingModel;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Converts each source file to cregit's token-per-line format: every leaf token on its own line as
 * {@code type|content}, with each class, method, or field wrapped in a matching {@code begin_} /
 * {@code end_} pair. Exposing a file's token stream this way lets Git follow history and blame at the
 * token level. Each blob is tokenized by a pluggable analysis backend; because the backends type
 * tokens differently (srcML element names vs grammar-derived types), their output is not
 * byte-identical.
 *
 * @see <a href="https://github.com/dmgerman/tokenizers">cregit tokenizer</a>
 */
@Slf4j
@ToString
@Command(name = "@cregit", description = "cregit format via srcML or tree-sitter")
public class Cregit implements BlobTranslator {
    static final String VERSION = "0.0.1";

    public enum BackendType { srcml, ts }

    /**
     * The analysis backends to try, in priority order; each file is handled by the first whose languages
     * include it.
     * <ul>
     * <li>{@code srcml}: srcML, C, C++, C#, and Java, preprocessor-aware.</li>
     * <li>{@code ts}: tree-sitter, every supported language.</li>
     * </ul>
     * The default {@code srcml,ts} falls back to tree-sitter for the languages srcML does not cover.
     */
    @Option(names = "--backend", split = ",", paramLabel = "<b>",
            description = "analysis backends in priority order (${COMPLETION-CANDIDATES}; default: ${DEFAULT-VALUE})")
    protected List<BackendType> backendNames = List.of(BackendType.srcml, BackendType.ts);

    @Mixin
    private final SrcmlAnalyzer srcmlAnalyzer = new SrcmlAnalyzer();

    // no options of its own, so a plain field rather than a mixin
    private final TreeSitterAnalyzer tsAnalyzer = new TreeSitterAnalyzer();

    /**
     * Whether each token line also carries the token's {@code line:column} position in the source.
     */
    @Option(names = "--position", description = "include line:column position in output")
    protected boolean position = false;

    @Mixin
    private final NameFilter filter = new NameFilter();

    /**
     * Forces the srcML source language, and, unless a name filter was given explicitly, restricts
     * processing to that language's file extensions.
     */
    @Option(names = {"-l", "--lang"}, description = "force the srcML language: C, C++, C#, or Java")
    protected void setLanguage(final String language) {
        srcmlAnalyzer.setLanguage(language);
        if (filter.isDefault()) {
            switch (language) {
                case "C" -> filter.setPatterns(globs(SrcmlAnalyzer.C_EXT));
                case "C++" -> filter.setPatterns(globs(SrcmlAnalyzer.CXX_EXT));
                case "C#" -> filter.setPatterns(globs(SrcmlAnalyzer.CSHARP_EXT));
                case "Java" -> filter.setPatterns(globs(SrcmlAnalyzer.JAVA_EXT));
                default -> log.error("Unknown language: {}", language);
            }
        }
    }

    private static String[] globs(final String[] suffixes) {
        return Stream.of(suffixes).map(s -> "*" + s).toArray(String[]::new);
    }

    /**
     * Selects the backends to try, in priority order (mainly for programmatic use); returns this.
     */
    public Cregit backends(final BackendType... names) {
        this.backendNames = List.of(names);
        return this;
    }

    private Analyzer.Tokenizing analyzer(final BackendType type) {
        return switch (type) {
            case srcml -> srcmlAnalyzer;
            case ts -> tsAnalyzer;
        };
    }

    private List<Analyzer.Tokenizing> orderedAnalyzers() {
        return backendNames.stream().map(this::analyzer).toList();
    }

    @Override
    public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
        if (!filter.accept(entry)) {
            return entry;
        }
        final Analyzer.Tokenizing analyzer = Analyzer.pick(orderedAnalyzers(), entry.getName());
        if (analyzer == null) {
            return entry;
        }
        log.debug("Generate cregit module for {} {}", entry, c);
        final TokenizingModel model = analyzer.analyze(entry.getName(), entry.getBlob(), c);
        return model == null ? entry : entry.update(convert(model, analyzer.languageName(entry.getName())));
    }

    /**
     * Walks the model's token stream into cregit format: each token as {@code type|content}, each
     * class/method/field wrapped in {@code begin_}/{@code end_}, the whole file in {@code begin_unit}/
     * {@code end_unit}.
     */
    private byte[] convert(final TokenizingModel model, final String language) {
        final StringBuilder sb = new StringBuilder();
        marker(sb, "begin_unit|language:" + language + ";cregit-version:" + VERSION);
        model.walkTokens(new TokenizingModel.TokenVisitor() {
            @Override
            public void begin(final Element.Kind kind) {
                marker(sb, "begin_" + kind.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public void token(final Token t) {
                if (position) {
                    sb.append(t.line()).append(":").append(t.column()).append("|");
                }
                sb.append(t.type()).append("|").append(t.text()).append("\n");
            }

            @Override
            public void end(final Element.Kind kind) {
                marker(sb, "end_" + kind.name().toLowerCase(Locale.ROOT));
            }
        });
        marker(sb, "end_unit");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void marker(final StringBuilder sb, final String line) {
        if (position) {
            sb.append("-:-|");
        }
        sb.append(line).append("\n");
    }
}
