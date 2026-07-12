package jp.ac.titech.c.se.stein.analyzer;

import java.util.List;

/**
 * A {@link SourceModel} that also carries the file's leaf-token stream, as neutral material a
 * consumer assembles into its own output -- cregit wraps each declaration in {@code begin_}/
 * {@code end_} markers, a FinerGit sequence drops comments and frame tokens. A backend that only
 * recovers structure yields a {@link SourceModel} alone.
 */
public interface TokenizingModel extends SourceModel {
    /**
     * The leaf tokens of an element, in source order, each carrying its {@link Token#comment} and
     * {@link Token#frame} classification, so a consumer can keep or drop them by its own policy.
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
