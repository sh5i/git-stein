package jp.ac.titech.c.se.stein.historage;

/**
 * The naming convention for the Python Historage generator: classes join with {@code .} and
 * members with {@code #}, and a top-level definition is separated from the file base with {@code !}.
 */
public class PythonNaming implements NamingStrategy {
    public static final PythonNaming INSTANCE = new PythonNaming();

    @Override
    public String basename(final Module m) {
        return switch (m.kind) {
            case FILE -> m.name;
            case CLASS -> basename(m.parent) + (m.parent.kind == Kind.FILE ? "!" : ".") + m.name;
            case METHOD, FIELD -> basename(m.parent) + (m.parent.kind == Kind.FILE ? "!" : "#") + m.name;
        };
    }
}
