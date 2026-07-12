package jp.ac.titech.c.se.stein.analyzer;

import java.util.function.Function;
import java.util.function.Supplier;

import org.treesitter.TSLanguage;
import org.treesitter.TSNode;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import lombok.extern.slf4j.Slf4j;

/**
 * One language's tree-sitter analyzer: it owns the grammar, the element-detection query written
 * against it, the filename filter, and the source decoder, and analyzing a file parses it and wraps
 * the tree in the language's {@link QueryModel}. Parsers are held per thread, since tree-sitter
 * parsers are not thread-safe; the query is compiled once on first use.
 */
@Slf4j
public abstract class QueryAnalyzer implements Analyzer.Tokenizing {
    private final String name;

    private final NameFilter filter;

    private final Function<byte[], String> decoder;

    private final Supplier<TSLanguage> language;

    private final String queryString;

    private final ThreadLocal<TSParser> parser;

    private TSQuery query;

    protected QueryAnalyzer(final String name, final NameFilter filter, final Function<byte[], String> decoder,
                            final Supplier<TSLanguage> language, final String queryString) {
        this.name = name;
        this.filter = filter;
        this.decoder = decoder;
        this.language = language;
        this.queryString = queryString;
        this.parser = ThreadLocal.withInitial(() -> {
            final TSParser p = new TSParser();
            p.setLanguage(language.get());
            return p;
        });
    }

    @Override
    public boolean accepts(final String filename) {
        return filter.accept(filename);
    }

    @Override
    public String languageName(final String filename) {
        return name;
    }

    @Override
    public TokenizingModel analyze(final String filename, final byte[] blob, final Context c) {
        final SourceText text = SourceText.ofNormalized(blob, decoder);
        final TSNode treeRoot = parser.get().parseString(null, text.getContent()).getRootNode();
        if (treeRoot.hasError()) {
            log.debug("Syntax errors found; extracting the elements that parsed");
        }
        return createModel(filename, text, treeRoot);
    }

    /**
     * Wraps a parsed tree in this language's model.
     */
    protected abstract TreeSitterModel createModel(String filename, SourceText text, TSNode treeRoot);

    /**
     * The compiled element-detection query, shared by every model this analyzer produces.
     */
    protected synchronized TSQuery query() {
        if (query == null) {
            query = new TSQuery(language.get(), queryString);
        }
        return query;
    }
}
