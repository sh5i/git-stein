package jp.ac.titech.c.se.stein.ts;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

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

import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import jp.ac.titech.c.se.stein.util.PythonSource;
import lombok.extern.slf4j.Slf4j;

/**
 * The registry of per-language tree-sitter analyzers. {@link #of} picks the first language whose
 * filename filter accepts a blob, parses it, and returns a ready {@link LanguageAnalyzer}; adding a
 * language is a matter of registering one more entry.
 */
@Slf4j
public final class Languages {
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

    /**
     * The registered languages, tried in order; the first whose filter accepts a blob handles it.
     */
    private static final List<Entry> ENTRIES = List.of(
            new Entry(PYTHON, TreeSitterPython::new, PythonSource::decode, PythonAnalyzer::new),
            new Entry(JAVA, TreeSitterJava::new, SourceText::ofNormalized, JavaAnalyzer::new),
            new Entry(CPP, TreeSitterCpp::new, SourceText::ofNormalized, CppAnalyzer::new),
            new Entry(CSHARP, TreeSitterCSharp::new, SourceText::ofNormalized, CSharpAnalyzer::new),
            new Entry(JAVASCRIPT, TreeSitterJavascript::new, SourceText::ofNormalized, JsAnalyzer::new),
            new Entry(TYPESCRIPT, TreeSitterTypescript::new, SourceText::ofNormalized, TsAnalyzer::new),
            // C is a subset of C++, so it reuses the C++ analyzer with the C grammar
            new Entry(C, TreeSitterC::new, SourceText::ofNormalized, CppAnalyzer::new),
            new Entry(GO, TreeSitterGo::new, SourceText::ofNormalized, GoAnalyzer::new),
            new Entry(KOTLIN, TreeSitterKotlin::new, SourceText::ofNormalized, KotlinAnalyzer::new),
            new Entry(RUST, TreeSitterRust::new, SourceText::ofNormalized, RustAnalyzer::new),
            new Entry(SWIFT, TreeSitterSwift::new, SourceText::ofNormalized, SwiftAnalyzer::new),
            new Entry(RUBY, TreeSitterRuby::new, SourceText::ofNormalized, RubyAnalyzer::new));

    private Languages() {
    }

    /**
     * Whether any registered language handles the given file name.
     */
    public static boolean accepts(final String filename) {
        return ENTRIES.stream().anyMatch(e -> e.filter.accept(filename));
    }

    /**
     * Parses the blob with the first language that accepts its file name and returns a ready
     * analyzer, or null when no language handles it.
     */
    public static LanguageAnalyzer of(final String filename, final byte[] blob) {
        final Entry entry = ENTRIES.stream().filter(e -> e.filter.accept(filename)).findFirst().orElse(null);
        if (entry == null) {
            return null;
        }
        final SourceText text = entry.decoder.apply(blob);
        final TSNode treeRoot = entry.parser.get().parseString(null, text.getContent()).getRootNode();
        if (treeRoot.hasError()) {
            log.debug("Syntax errors found; extracting the elements that parsed");
        }
        return entry.factory.create(filename, text, treeRoot);
    }

    /**
     * Builds a {@link LanguageAnalyzer} from an already-parsed tree.
     */
    private interface Factory {
        LanguageAnalyzer create(String filename, SourceText text, TSNode treeRoot);
    }

    /**
     * The per-language wiring: which files it handles, how to decode and parse them, and how to build
     * its analyzer. Parsers are held per thread and language, since tree-sitter parsers are not
     * thread-safe.
     */
    private static final class Entry {
        private final NameFilter filter;

        private final ThreadLocal<TSParser> parser;

        private final Function<byte[], SourceText> decoder;

        private final Factory factory;

        Entry(final NameFilter filter, final Supplier<TSLanguage> language,
              final Function<byte[], SourceText> decoder, final Factory factory) {
            this.filter = filter;
            this.parser = ThreadLocal.withInitial(() -> {
                final TSParser p = new TSParser();
                p.setLanguage(language.get());
                return p;
            });
            this.decoder = decoder;
            this.factory = factory;
        }
    }
}
