package jp.ac.titech.c.se.stein;

import java.util.Arrays;

import jp.ac.titech.c.se.stein.core.RepositoryRewriter;

public class Loader {
    /**
     * Loads a rewriter class from the given name and instantiates it.
     */
    public RepositoryRewriter load(final String name) {
        final Class<? extends RepositoryRewriter> klass = loadClass(name);
        return klass == null ? null : newInstance(klass);
    }

    /**
     * Instantiates the given class.
     */
    protected static RepositoryRewriter newInstance(final Class<? extends RepositoryRewriter> klass) {
        try {
            return klass.getDeclaredConstructor().newInstance();
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Loads a rewriter class from the given name.
     */
    protected Class<? extends RepositoryRewriter> loadClass(final String name) {
        Class<? extends RepositoryRewriter> result = tryLoadClass(name);
        if (result == null) {
            result = tryLoadClass(Loader.class.getPackage().getName() + ".app." + name);
        }
        if (result == null) {
            result = tryLoadClass(Loader.class.getPackage().getName() + "." + name);
        }
        return result;
    }

    /**
     * Loads a rewriter class from the given class name.
     */
    protected static Class<? extends RepositoryRewriter> tryLoadClass(final String name) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends RepositoryRewriter> result = (Class<? extends RepositoryRewriter>) Class.forName(name);
            return result;
        } catch (final ClassNotFoundException | ClassCastException e) {
            return null;
        }
    }

    public static void main(final String[] args) {
        if (args.length == 0) {
            System.err.println("java -jar path/to/git-stein.jar AppName [args...]");
            System.err.println("Specify the app name to be executed at the first parameter.");
            System.exit(1);
        }
        final String className = args[0];
        final RepositoryRewriter rewriter = new Loader().load(className);
        final String[] realArgs = Arrays.copyOfRange(args, 1, args.length);
        Application.execute(rewriter, realArgs);
    }
}
