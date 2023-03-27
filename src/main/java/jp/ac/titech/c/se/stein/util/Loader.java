package jp.ac.titech.c.se.stein.util;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.RewriterCommand;

public class Loader {
    /**
     * Loads a rewriter class from the given name and instantiates it.
     */
    public static RewriterCommand load(final String name) {
        final Class<? extends RewriterCommand> klass = loadClass(name);
        return klass == null ? null : newInstance(klass);
    }

    /**
     * Instantiates the given class.
     */
    public static RewriterCommand newInstance(final Class<? extends RewriterCommand> klass) {
        try {
            return klass.getDeclaredConstructor().newInstance();
        } catch (final ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Loads a rewriter class from the given name.
     */
    public static Class<? extends RewriterCommand> loadClass(final String name) {
        Class<? extends RewriterCommand> result = tryLoadClass(name);
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
    protected static Class<? extends RewriterCommand> tryLoadClass(final String name) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends RewriterCommand> result = (Class<? extends RewriterCommand>) Class.forName(name);
            return result;
        } catch (final ClassNotFoundException | ClassCastException e) {
            return null;
        }
    }
}
