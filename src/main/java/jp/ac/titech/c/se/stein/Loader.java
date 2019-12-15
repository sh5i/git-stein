package jp.ac.titech.c.se.stein;

import java.util.Arrays;

import jp.ac.titech.c.se.stein.core.RepositoryRewriter;

public class Loader {
    public RepositoryRewriter load(final String name) {
        final Class<? extends RepositoryRewriter> klass = loadClass(name);
        return klass == null ? null : newInstance(klass);
    }

    protected static RepositoryRewriter newInstance(final Class<? extends RepositoryRewriter> klass) {
        try {
            return klass.newInstance();
        } catch (final InstantiationException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

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

    protected static Class<? extends RepositoryRewriter> tryLoadClass(final String name) {
        try {
            @SuppressWarnings("unchecked")
            final Class<? extends RepositoryRewriter> result = (Class<? extends RepositoryRewriter>) Class.forName(name);
            return result;
        } catch (final ClassNotFoundException e) {
            return null;
        } catch (final ClassCastException e) {
            return null;
        }
    }

    public static void main(final String[] args) {
        final String className = args[0];
        final String[] realArgs = Arrays.copyOfRange(args, 1, args.length);
        new CLI(new Loader().load(className), realArgs).run();
    }
}
