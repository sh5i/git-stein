package jp.ac.titech.c.se.stein.analyzer;

import java.util.List;

import jp.ac.titech.c.se.stein.analyzer.ts.*;
import jp.ac.titech.c.se.stein.core.Context;

/**
 * The tree-sitter analyzer: a registry of per-language {@link QueryAnalyzer}s; the one whose
 * {@link Language} claims the file handles it. It has no options; adding a language is a matter of
 * registering one more entry.
 */
public class TreeSitterAnalyzer implements Analyzer.Tokenizing {
    private static final List<QueryAnalyzer> LANGUAGES = List.of(
            new PythonAnalyzer(),
            new JavaAnalyzer(),
            new CppAnalyzer(false),
            new CSharpAnalyzer(),
            new JavaScriptAnalyzer(),
            new TypeScriptAnalyzer(),
            new CppAnalyzer(true),
            new GoAnalyzer(),
            new KotlinAnalyzer(),
            new RustAnalyzer(),
            new SwiftAnalyzer(),
            new RubyAnalyzer(),
            new PhpAnalyzer(),
            new DartAnalyzer(),
            new ObjectiveCAnalyzer(),
            new RAnalyzer(),
            new BashAnalyzer(),
            new SqlAnalyzer(),
            new HtmlAnalyzer());

    @Override
    public boolean accepts(final String filename) {
        return Analyzer.pick(LANGUAGES, filename) != null;
    }

    @Override
    public TokenizingModel analyze(final String filename, final byte[] blob, final Context c) {
        final QueryAnalyzer language = Analyzer.pick(LANGUAGES, filename);
        return language == null ? null : language.analyze(filename, blob, c);
    }

    @Override
    public Language languageOf(final String filename) {
        final QueryAnalyzer language = Analyzer.pick(LANGUAGES, filename);
        return language == null ? null : language.languageOf(filename);
    }
}
