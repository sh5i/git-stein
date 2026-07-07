package jp.ac.titech.c.se.stein.historage;

import lombok.RequiredArgsConstructor;

/**
 * The category of a source element extracted into a Historage module, and the single-letter marker
 * ({@code c}/{@code m}/{@code f}) the finer-grained naming conventions use in file extensions.
 */
@RequiredArgsConstructor
public enum Kind {
    FILE(null), CLASS("c"), METHOD("m"), FIELD("f");

    private final String letter;

    /**
     * The module file extension for this kind derived from the source file name; for example
     * {@code CLASS.extension("Foo.java")} is {@code ".cjava"} and {@code METHOD.extension("a.py")}
     * is {@code ".mpy"}.
     */
    public String extension(final String sourceName) {
        return "." + letter + sourceName.substring(sourceName.lastIndexOf('.') + 1);
    }
}
