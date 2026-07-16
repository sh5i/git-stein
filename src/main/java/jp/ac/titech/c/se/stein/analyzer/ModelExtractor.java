package jp.ac.titech.c.se.stein.analyzer;

import java.util.List;

import jp.ac.titech.c.se.stein.core.Context;

/**
 * An {@link Analyzer} that recovers a file's structure, producing a per-file {@link SourceModel} (each
 * implementation nests its model class as its {@code Model}).
 */
public interface ModelExtractor extends Analyzer {
    /**
     * Extracts one file's model, or returns null when the analysis fails.
     */
    SourceModel extract(String filename, byte[] blob, Context c);

    /**
     * A {@link ModelExtractor} whose models also carry a token stream, for a consumer such as cregit or
     * a FinerGit token rendering. It is a {@link Tokenizer} as well, satisfied out of its own model, so
     * a whole-file token consumer takes it alongside a lexical tokenizer. A structure-only extractor
     * implements {@link ModelExtractor} alone, so a token-consuming app holding a list of
     * {@code Tokenizing} cannot be handed one that only recovers structure.
     */
    interface Tokenizing extends ModelExtractor, Tokenizer {
        @Override
        TokenizingModel extract(String filename, byte[] blob, Context c);

        @Override
        default List<Token> tokenize(final String filename, final byte[] blob, final Context c) {
            final TokenizingModel model = extract(filename, blob, c);
            return model == null ? null : model.getTokens(model.getRoot());
        }
    }
}
