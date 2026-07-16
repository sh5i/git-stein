package jp.ac.titech.c.se.stein.analyzer;

import java.util.List;

/**
 * One analysis agent, living for the whole rewriting process: it decides which files it handles and
 * names their language. What an agent produces is its capability, not its identity: a
 * {@link ModelExtractor} recovers a file's structure, a {@link Tokenizer} turns the file into tokens,
 * and an agent that does both implements {@link ModelExtractor.Tokenizing} -- so a lexical tokenizer,
 * having no structure to give, is an analyzer all the same. An agent carries its own configuration
 * (including its command-line options), so an app mixes in only the agents it supports.
 */
public interface Analyzer {
    boolean accepts(String filename);

    /**
     * The source language this analyzer assigns to the given file, or null when it cannot name one
     * (e.g. ctags, which detects languages internally).
     */
    default Language languageOf(final String filename) {
        return null;
    }

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
}
