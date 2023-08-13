package jp.ac.titech.c.se.stein.util;

import com.google.common.reflect.ClassPath;
import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.rewriter.RewriterCommand;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.stream.Collectors;

@Slf4j
public class Loader {
    /**
     * Loads a rewriter class from the given name and instantiates it.
     */
    @SuppressWarnings("unused")
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
            result = tryLoadClass(Application.BUILTIN_COMMAND_PACKAGE + "." + name);
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

    /**
     * Enumerates git-stein commands from the given package.
     */
    public static Collection<Class<? extends RewriterCommand>> enumerateCommands(final String pkg, final boolean isRecursive) {
        final ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try {
            final ClassPath cp = ClassPath.from(loader);
            @SuppressWarnings("unchecked")
            final Collection<Class<? extends RewriterCommand>> result =
                    (isRecursive ? cp.getTopLevelClassesRecursive(pkg) : cp.getTopLevelClasses(pkg))
                            .stream()
                            .map(info -> (Class<? extends RewriterCommand>) info.load())
                            .filter(c -> c.isAnnotationPresent(Command.class))
                            .sorted(Comparator.comparing(c -> c.getAnnotation(Command.class).name()))
                            .collect(Collectors.toList());
            return result;
        } catch (final IOException e) {
            log.error("Loading command: " + e.getMessage(), e);
            return Collections.emptyList();
        }
    }
}
