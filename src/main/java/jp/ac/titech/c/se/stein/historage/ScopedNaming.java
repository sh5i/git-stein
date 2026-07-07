package jp.ac.titech.c.se.stein.historage;

/**
 * The scoped naming convention shared by languages with explicit namespaces or packages: namespaces
 * and classes nest with {@code .} and members with {@code #}, and a top-level definition is
 * separated from the file base with {@code !}. It stays portable across file systems by using no
 * reserved characters. Used by the Python, C++, and C# generators.
 */
public class ScopedNaming implements NamingStrategy {
    public static final ScopedNaming INSTANCE = new ScopedNaming();

    @Override
    public String basename(final Module m) {
        return switch (m.kind) {
            case FILE -> m.name;
            case CLASS -> basename(m.parent) + (m.parent.kind == Kind.FILE ? "!" : ".") + m.name;
            case METHOD, FIELD -> basename(m.parent) + (m.parent.kind == Kind.FILE ? "!" : "#") + m.name;
        };
    }
}
