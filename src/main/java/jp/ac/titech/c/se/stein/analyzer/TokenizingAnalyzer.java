package jp.ac.titech.c.se.stein.analyzer;

import java.util.List;

/**
 * A {@link SourceAnalyzer} that can also tokenize: beyond extracting and rendering elements, it
 * exposes the file's leaf-token stream, so a consumer such as cregit can wrap each declaration in
 * {@code begin_}/{@code end_} markers. A backend that only recovers structure implements {@link
 * SourceAnalyzer} alone.
 */
public interface TokenizingAnalyzer extends SourceAnalyzer {
    /**
     * The leaf tokens of an element, in source order.
     */
    List<Token> tokens(Element e);

    /**
     * Walks the whole file's token stream, wrapping each extracted class/method/field element (by its
     * source range) in a {@link TokenVisitor#begin}/{@link TokenVisitor#end} pair.
     */
    void walkTokens(TokenVisitor sink);

    /**
     * A visitor over the structured token stream: a class/method/field element opens with {@link
     * #begin} before its tokens and closes with {@link #end} after them, so a consumer such as cregit
     * can wrap each declaration in {@code begin_}/{@code end_} markers.
     */
    interface TokenVisitor {
        void begin(Element.Kind kind);

        void token(Token token);

        void end(Element.Kind kind);
    }
}
