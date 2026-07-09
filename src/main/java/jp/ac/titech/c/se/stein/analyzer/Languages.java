package jp.ac.titech.c.se.stein.analyzer;

import jp.ac.titech.c.se.stein.analyzer.ts.*;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TreeSitterBash;
import org.treesitter.TreeSitterC;
import org.treesitter.TreeSitterCSharp;
import org.treesitter.TreeSitterCpp;
import org.treesitter.TreeSitterDart;
import org.treesitter.TreeSitterGo;
import org.treesitter.TreeSitterHtml;
import org.treesitter.TreeSitterJava;
import org.treesitter.TreeSitterJavascript;
import org.treesitter.TreeSitterKotlin;
import org.treesitter.TreeSitterObjc;
import org.treesitter.TreeSitterPhp;
import org.treesitter.TreeSitterPython;
import org.treesitter.TreeSitterR;
import org.treesitter.TreeSitterRuby;
import org.treesitter.TreeSitterRust;
import org.treesitter.TreeSitterSql;
import org.treesitter.TreeSitterSwift;
import org.treesitter.TreeSitterTypescript;

import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import jp.ac.titech.c.se.stein.util.PythonSource;
import lombok.extern.slf4j.Slf4j;

/**
 * The registry of per-language tree-sitter analyzers. {@link #of} picks the first language whose
 * filename filter accepts a blob, parses it, and returns a ready {@link TreeSitterAnalyzer}; adding a
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

    public static final NameFilter PHP = new NameFilter(true, "*.php", "*.phtml");

    public static final NameFilter DART = new NameFilter(true, "*.dart");

    public static final NameFilter OBJC = new NameFilter(true, "*.m", "*.mm");

    public static final NameFilter R = new NameFilter(true, "*.r");

    public static final NameFilter SHELL = new NameFilter(true, "*.sh", "*.bash", "*.zsh");

    public static final NameFilter SQL = new NameFilter(true, "*.sql");

    public static final NameFilter HTML = new NameFilter(true, "*.html", "*.htm");

    /**
     * The registered languages, tried in order; the first whose filter accepts a blob handles it.
     */
    private static final List<Entry> ENTRIES = List.of(
            new Entry("Python", PYTHON, TreeSitterPython::new, PythonSource::decode, PythonAnalyzer::new),
            new Entry("Java", JAVA, TreeSitterJava::new, SourceText::ofNormalized, JavaAnalyzer::new),
            new Entry("C++", CPP, TreeSitterCpp::new, SourceText::ofNormalized, CppAnalyzer::new),
            new Entry("C#", CSHARP, TreeSitterCSharp::new, SourceText::ofNormalized, CSharpAnalyzer::new),
            new Entry("JavaScript", JAVASCRIPT, TreeSitterJavascript::new, SourceText::ofNormalized, JsAnalyzer::new),
            new Entry("TypeScript", TYPESCRIPT, TreeSitterTypescript::new, SourceText::ofNormalized, TsAnalyzer::new),
            // C is a subset of C++, so it reuses the C++ analyzer with the C grammar
            new Entry("C", C, TreeSitterC::new, SourceText::ofNormalized, CppAnalyzer::new),
            new Entry("Go", GO, TreeSitterGo::new, SourceText::ofNormalized, GoAnalyzer::new),
            new Entry("Kotlin", KOTLIN, TreeSitterKotlin::new, SourceText::ofNormalized, KotlinAnalyzer::new),
            new Entry("Rust", RUST, TreeSitterRust::new, SourceText::ofNormalized, RustAnalyzer::new),
            new Entry("Swift", SWIFT, TreeSitterSwift::new, SourceText::ofNormalized, SwiftAnalyzer::new),
            new Entry("Ruby", RUBY, TreeSitterRuby::new, SourceText::ofNormalized, RubyAnalyzer::new),
            new Entry("PHP", PHP, TreeSitterPhp::new, SourceText::ofNormalized, PhpAnalyzer::new),
            new Entry("Dart", DART, TreeSitterDart::new, SourceText::ofNormalized, DartAnalyzer::new),
            new Entry("Objective-C", OBJC, TreeSitterObjc::new, SourceText::ofNormalized, ObjcAnalyzer::new),
            new Entry("R", R, TreeSitterR::new, SourceText::ofNormalized, RAnalyzer::new),
            new Entry("Shell", SHELL, TreeSitterBash::new, SourceText::ofNormalized, BashAnalyzer::new),
            new Entry("SQL", SQL, TreeSitterSql::new, SourceText::ofNormalized, SqlAnalyzer::new),
            new Entry("HTML", HTML, TreeSitterHtml::new, SourceText::ofNormalized, HtmlAnalyzer::new));

    private Languages() {
    }

    /**
     * Whether any registered language handles the given file name.
     */
    public static boolean accepts(final String filename) {
        return ENTRIES.stream().anyMatch(e -> e.filter.accept(filename));
    }

    /**
     * The display name of the first language that accepts the given file name, or null when none does.
     */
    public static String nameOf(final String filename) {
        return ENTRIES.stream().filter(e -> e.filter.accept(filename)).findFirst().map(e -> e.name).orElse(null);
    }

    /**
     * Parses the blob with the first language that accepts its file name and returns a ready
     * analyzer, or null when no language handles it.
     */
    public static TokenizingAnalyzer of(final String filename, final byte[] blob) {
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
     * Builds a {@link TreeSitterAnalyzer} from an already-parsed tree.
     */
    private interface Factory {
        TreeSitterAnalyzer create(String filename, SourceText text, TSNode treeRoot);
    }

    /**
     * The per-language wiring: which files it handles, how to decode and parse them, and how to build
     * its analyzer. Parsers are held per thread and language, since tree-sitter parsers are not
     * thread-safe.
     */
    private static final class Entry {
        private final String name;

        private final NameFilter filter;

        private final ThreadLocal<TSParser> parser;

        private final Function<byte[], SourceText> decoder;

        private final Factory factory;

        Entry(final String name, final NameFilter filter, final Supplier<TSLanguage> language,
              final Function<byte[], SourceText> decoder, final Factory factory) {
            this.name = name;
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
