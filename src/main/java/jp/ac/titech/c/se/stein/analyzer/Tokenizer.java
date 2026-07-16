package jp.ac.titech.c.se.stein.analyzer;

import java.util.List;

import jp.ac.titech.c.se.stein.core.Context;

/**
 * An {@link Analyzer} that turns a file into a flat token stream, recovering no structure. A lexical
 * tokenizer -- a regular-expression scanner, a compiler front end's lexer -- implements this alone; an
 * agent that also recovers structure implements {@link ModelExtractor.Tokenizing}.
 */
public interface Tokenizer extends Analyzer {
    /**
     * Tokenizes one whole file, in source order, or returns null when the tokenization fails.
     */
    List<Token> tokenize(String filename, byte[] blob, Context c);
}
