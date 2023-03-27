package jp.ac.titech.c.se.stein.util;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;

public class Loader {
    /**
     * Loads a rewriter class from the given name and instantiates it.
     */
    public static RepositoryRewriter load(final String name) {
        final Class<? extends RepositoryRewriter> klass = loadClass(name);
        return klass == null ? null : newInstance(klass);
    }

    /**
     * Instantiates the given class.
     */
    public static RepositoryRewriter newInstance(final Class<? extends RepositoryRewriter> klass) {
        try {
            return klass.getDeclaredConstructor().newInstance();
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Loads a rewriter class from the given name.
     */
    public static Class<? extends RepositoryRewriter> loadClass(final String name) {
        Class<? extends RepositoryRewriter> result = tryLoadClass(name);
        if (result == null) {
            result = tryLoadClass(Application.class.getPackage().getName() + ".app." + name);
        }
        if (result == null) {
            result = tryLoadClass(Application.class.getPackage().getName() + "." + name);
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
}
