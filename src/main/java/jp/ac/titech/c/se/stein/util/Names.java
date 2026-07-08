package jp.ac.titech.c.se.stein.util;

/**
 * Turns a source-level name (an identifier, a qualified name, or a rendered signature) into a
 * portable file-name component, replacing white space and the characters reserved on common file
 * systems. Shared by the tree-sitter analyzers and the ctags-based Historage generator.
 */
public final class Names {
    private Names() {
    }

    public static String escape(final String s) {
        return escapeReservedCharacters(s.trim().replaceAll("\\s+", "~"));
    }

    public static String escapeReservedCharacters(final String s) {
        // https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-file
        return s.replace('<', '[')
                .replace('>', ']')
                .replace(':', ';')
                .replace('"', '\'')
                .replace('/', '%')
                .replace('\\', '%')
                .replace('|', '!')
                .replace('?', '#')
                .replace('*', '+')
                .replaceAll("[\\x00-\\x1F]", "");
    }
}
