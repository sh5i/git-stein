package jp.ac.titech.c.se.stein.historage;

/**
 * The FinerGit naming convention used by the Java Historage generators. Nested classes join with
 * {@code .} and members with {@code #}; a top-level class whose name differs from the file base is
 * written as {@code Name[FileBase]}.
 */
public class FinerGitNaming implements NamingStrategy {
    public static final FinerGitNaming INSTANCE = new FinerGitNaming();

    @Override
    public String basename(final Module m) {
        return switch (m.kind) {
            case FILE -> m.name;
            case CLASS -> m.parent.kind == Kind.CLASS
                    ? basename(m.parent) + "." + m.name
                    : basename(m.parent).equals(m.name) ? m.name : m.name + "[" + basename(m.parent) + "]";
            case METHOD, FIELD -> basename(m.parent) + "#" + m.name;
        };
    }
}
