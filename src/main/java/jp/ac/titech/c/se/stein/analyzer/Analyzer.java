package jp.ac.titech.c.se.stein.analyzer;

import java.util.List;

import jp.ac.titech.c.se.stein.core.Context;

/**
 * One analysis agent, living for the whole rewriting process: it decides which files it handles and
 * runs one analysis per file, producing a per-file {@link SourceModel} (each implementation nests its
 * model class as its {@code Model}). An agent carries its own configuration (including its
 * command-line options), so an app mixes in only the agents it supports. An agent that can also
 * tokenize implements {@link Tokenizing}.
 */
public interface Analyzer {
    boolean accepts(String filename);

    /**
     * The human-readable source language name this analyzer assigns to the given file (for example a
     * cregit header), or null when it cannot name it.
     */
    default String languageName(final String filename) {
        return null;
    }

    /**
     * Analyzes one file, or returns null when the analysis fails.
     */
    SourceModel analyze(String filename, byte[] blob, Context c);

    /**
     * Picks the first analyzer, in priority order, that accepts the file.
     */
    static <A extends Analyzer> A pick(final List<A> analyzers, final String filename) {
        for (final A analyzer : analyzers) {
            if (analyzer.accepts(filename)) {
                return analyzer;
            }
        }
        return null;
    }

    /**
     * An {@link Analyzer} whose models also carry a token stream, for a consumer such as cregit or a
     * FinerGit token rendering. A structure-only analyzer implements {@link Analyzer} alone, so a
     * token-consuming app holding a list of {@code Tokenizing} cannot be handed one that only recovers
     * structure.
     */
    interface Tokenizing extends Analyzer {
        @Override
        TokenizingModel analyze(String filename, byte[] blob, Context c);
    }
}
