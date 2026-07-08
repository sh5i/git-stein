package jp.ac.titech.c.se.stein.ts;

/**
 * The category of a source element a {@link LanguageAnalyzer} extracts: the virtual file root, a
 * class-like scope, a method, or a field. It is deliberately language-neutral; a consumer maps it to
 * its own model.
 */
public enum ElementKind {
    FILE, CLASS, METHOD, FIELD
}
