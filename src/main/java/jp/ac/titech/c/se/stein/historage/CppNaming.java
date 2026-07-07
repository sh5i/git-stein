package jp.ac.titech.c.se.stein.historage;

/**
 * The naming convention for the C++ Historage generator: namespaces and classes nest with
 * {@code .} and members with {@code #}, and a top-level definition is separated from the file base
 * with {@code !}. It coincides with {@link PythonNaming} today but is kept separate because the two
 * languages' scoping rules may diverge.
 */
public class CppNaming implements NamingStrategy {
    public static final CppNaming INSTANCE = new CppNaming();

    @Override
    public String basename(final Module m) {
        return switch (m.kind) {
            case FILE -> m.name;
            case CLASS -> basename(m.parent) + (m.parent.kind == Kind.FILE ? "!" : ".") + m.name;
            case METHOD, FIELD -> basename(m.parent) + (m.parent.kind == Kind.FILE ? "!" : "#") + m.name;
        };
    }
}
