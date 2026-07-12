package jp.ac.titech.c.se.stein.analyzer;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import jp.ac.titech.c.se.stein.core.SourceEncoding;

/**
 * A source language, the cross-analyzer concept the backends share: its display name (also the name
 * external tools such as srcML use), the file extensions it claims, and how its files are decoded
 * (Python and Ruby honor an encoding magic comment).
 *
 * <p>{@link #of} is the single extension-based dispatch every analyzer consults. An extension may be
 * claimed by several languages ({@code .h} is both C++ and C); the declaration order is the dispatch
 * priority, so C++, declared first, answers for a {@code .h}. A file that matches no extension
 * directly is retried with its kind letter stripped ({@code .mjava} is Java), so Historage modules
 * answer as their language; a real extension that happens to look like one ({@code .mjs}, {@code
 * .cts}) is unaffected, since the direct match runs first.</p>
 */
public enum Language {
    JAVA("Java", SourceEncoding::decode, ".java", ".aj"),
    CPP("C++", SourceEncoding::decode,
            ".cpp", ".cc", ".cxx", ".c++", ".cp", ".hpp", ".hh", ".hxx", ".h++", ".h", ".tcc", ".ii"),
    C("C", SourceEncoding::decode, ".c", ".h", ".i"),
    CSHARP("C#", SourceEncoding::decode, ".cs"),
    PYTHON("Python", SourceEncoding::decodeWithMagicComment, ".py"),
    JAVASCRIPT("JavaScript", SourceEncoding::decode, ".js", ".mjs", ".cjs", ".jsx"),
    TYPESCRIPT("TypeScript", SourceEncoding::decode, ".ts", ".mts", ".cts"),
    GO("Go", SourceEncoding::decode, ".go"),
    KOTLIN("Kotlin", SourceEncoding::decode, ".kt", ".kts"),
    RUST("Rust", SourceEncoding::decode, ".rs"),
    SWIFT("Swift", SourceEncoding::decode, ".swift"),
    RUBY("Ruby", SourceEncoding::decodeWithMagicComment, ".rb"),
    PHP("PHP", SourceEncoding::decode, ".php", ".phtml"),
    DART("Dart", SourceEncoding::decode, ".dart"),
    OBJC("Objective-C", SourceEncoding::decode, ".m", ".mm"),
    R("R", SourceEncoding::decode, ".r"),
    SHELL("Shell", SourceEncoding::decode, ".sh", ".bash", ".zsh"),
    SQL("SQL", SourceEncoding::decode, ".sql"),
    HTML("HTML", SourceEncoding::decode, ".html", ".htm");

    private final String name;

    private final Function<byte[], String> decoder;

    private final List<String> extensions;

    Language(final String name, final Function<byte[], String> decoder, final String... extensions) {
        this.name = name;
        this.decoder = decoder;
        this.extensions = List.of(extensions);
    }

    /**
     * The display name, as used in a cregit header and by srcML's {@code --language}.
     */
    public String getName() {
        return name;
    }

    /**
     * Decodes a blob of this language to a string (Python and Ruby honor an encoding magic comment).
     */
    public String decode(final byte[] raw) {
        return decoder.apply(raw);
    }

    /**
     * The file extensions this language claims, each with its leading dot.
     */
    public List<String> getExtensions() {
        return extensions;
    }

    /**
     * The language claiming the given file name, matched case-insensitively by extension, or null when
     * none does. When several languages claim the extension, the one declared first wins. A name that
     * matches no extension directly is retried with the kind letter of a Historage module name
     * stripped ({@code c}/{@code m}/{@code f}, so {@code Foo.mjava} is Java).
     */
    public static Language of(final String filename) {
        final String lower = filename.toLowerCase(Locale.ROOT);
        final Language direct = byExtension(lower);
        if (direct != null) {
            return direct;
        }
        final int dot = lower.lastIndexOf('.');
        if (dot >= 0 && dot + 2 < lower.length()) {
            final char kind = lower.charAt(dot + 1);
            if (kind == 'c' || kind == 'm' || kind == 'f') {
                return byExtension(lower.substring(0, dot + 1) + lower.substring(dot + 2));
            }
        }
        return null;
    }

    private static Language byExtension(final String lowerName) {
        for (final Language language : values()) {
            for (final String extension : language.extensions) {
                if (lowerName.endsWith(extension)) {
                    return language;
                }
            }
        }
        return null;
    }

    /**
     * The language with the given display name (e.g. {@code C++}), or null when there is none.
     */
    public static Language ofName(final String name) {
        for (final Language language : values()) {
            if (language.name.equals(name)) {
                return language;
            }
        }
        return null;
    }
}
