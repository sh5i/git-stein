package jp.ac.titech.c.se.stein.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;

/**
 * A utility interface for wrapping checked exceptions in unchecked ones within lambda expressions.
 *
 * <p>Provides two families of static methods:</p>
 * <ul>
 *   <li>{@code run} — wraps any {@link Exception} into {@link RuntimeException}</li>
 *   <li>{@code io} — wraps {@link IOException} into {@link UncheckedIOException}</li>
 * </ul>
 *
 * <p>Each family has three overloads corresponding to {@link Runnable}-like (void),
 * {@link java.util.function.Supplier}-like (returns a value), and
 * {@link Function}-like (takes an argument and returns a value) functional interfaces.
 * Each overload also has a variant that accepts a {@link Context} for enriched error messages.</p>
 *
 * <p>Example usage:</p>
 * <pre>{@code
 * // Wrap a void operation
 * Try.io(() -> Files.delete(path));
 *
 * // Wrap a supplier
 * byte[] data = Try.io(() -> Files.readAllBytes(path));
 *
 * // Wrap a function for use in streams
 * paths.stream().map(Try.io(p -> Files.readString(p))).collect(...);
 * }</pre>
 */
public interface Try {

    /**
     * A {@link Runnable}-like functional interface that may throw any {@link Exception}.
     */
    @FunctionalInterface
    interface ThrowableRunnable {
        void run() throws Exception;
    }

    /**
     * A {@link java.util.function.Supplier}-like functional interface that may throw any {@link Exception}.
     */
    @FunctionalInterface
    interface ThrowableSupplier<T> {
        T get() throws Exception;
    }

    /**
     * A {@link Function}-like functional interface that may throw any {@link Exception}.
     */
    @FunctionalInterface
    interface ThrowableFunction<T, R> {
        R apply(T t) throws Exception;
    }

    // ------

    /**
     * A {@link Runnable}-like functional interface that may throw {@link IOException}.
     */
    @FunctionalInterface
    interface IOThrowableRunnable {
        void run() throws IOException;
    }

    /**
     * A {@link java.util.function.Supplier}-like functional interface that may throw {@link IOException}.
     */
    @FunctionalInterface
    interface IOThrowableSupplier<T> {
        T get() throws IOException;
    }

    /**
     * A {@link Function}-like functional interface that may throw {@link IOException}.
     */
    @FunctionalInterface
    interface IOThrowableFunction<T, R> {
        R apply(T t) throws IOException;
    }

    // ------

    /**
     * Executes the given runnable, wrapping any {@link Exception} into {@link RuntimeException}.
     *
     * @param f the operation to execute
     */
    static void run(final ThrowableRunnable f) {
        try {
            f.run();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Executes the given runnable, wrapping any {@link Exception} into {@link RuntimeException}
     * with a message that includes the given {@link Context}.
     *
     * @param c the context for error reporting
     * @param f the operation to execute
     */
    static void run(final Context c, final ThrowableRunnable f) {
        try {
            f.run();
        } catch (final Exception e) {
            throw new RuntimeException("Exception raised " + c, e);
        }
    }

    /**
     * Executes the given supplier, wrapping any {@link Exception} into {@link RuntimeException}.
     *
     * @param f the supplier to execute
     * @return the result of the supplier
     */
    static <T> T run(final ThrowableSupplier<T> f) {
        try {
            return f.get();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Executes the given supplier, wrapping any {@link Exception} into {@link RuntimeException}
     * with a message that includes the given {@link Context}.
     *
     * @param c the context for error reporting
     * @param f the supplier to execute
     * @return the result of the supplier
     */
    static <T> T run(final Context c, final ThrowableSupplier<T> f) {
        try {
            return f.get();
        } catch (final Exception e) {
            throw new RuntimeException("Exception raised " + c, e);
        }
    }

    /**
     * Converts the given function into a standard {@link Function}, wrapping any {@link Exception}
     * into {@link RuntimeException}.
     *
     * @param f the function to convert
     * @return a {@link Function} that delegates to {@code f}
     */
    static <T, R> Function<T, R> run(final ThrowableFunction<T, R> f) {
        return x -> {
            try {
                return f.apply(x);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * Converts the given function into a standard {@link Function}, wrapping any {@link Exception}
     * into {@link RuntimeException} with a message that includes the given {@link Context}.
     *
     * @param c the context for error reporting
     * @param f the function to convert
     * @return a {@link Function} that delegates to {@code f}
     */
    static <T, R> Function<T, R> run(final Context c, final ThrowableFunction<T, R> f) {
        return x -> {
            try {
                return f.apply(x);
            } catch (final Exception e) {
                throw new RuntimeException("Exception raised " + c, e);
            }
        };
    }

    // ------

    /**
     * Executes the given runnable, wrapping any {@link IOException} into {@link UncheckedIOException}.
     *
     * @param f the operation to execute
     */
    static void io(final IOThrowableRunnable f) {
        try {
            f.run();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Executes the given runnable, wrapping any {@link IOException} into {@link UncheckedIOException}
     * with a message that includes the given {@link Context}.
     *
     * @param c the context for error reporting
     * @param f the operation to execute
     */
    static void io(final Context c, final IOThrowableRunnable f) {
        try {
            f.run();
        } catch (final IOException e) {
            throw new UncheckedIOException("Exception raised " + c, e);
        }
    }

    /**
     * Executes the given supplier, wrapping any {@link IOException} into {@link UncheckedIOException}.
     *
     * @param f the supplier to execute
     * @return the result of the supplier
     */
    static <T> T io(final IOThrowableSupplier<T> f) {
        try {
            return f.get();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Executes the given supplier, wrapping any {@link IOException} into {@link UncheckedIOException}
     * with a message that includes the given {@link Context}.
     *
     * @param c the context for error reporting
     * @param f the supplier to execute
     * @return the result of the supplier
     */
    static <T> T io(final Context c, final IOThrowableSupplier<T> f) {
        try {
            return f.get();
        } catch (final IOException e) {
            throw new UncheckedIOException("Exception raised " + c, e);
        }
    }

    /**
     * Converts the given function into a standard {@link Function}, wrapping any {@link IOException}
     * into {@link UncheckedIOException}.
     *
     * @param f the function to convert
     * @return a {@link Function} that delegates to {@code f}
     */
    static <T, R> Function<T, R> io(final IOThrowableFunction<T, R> f) {
        return x -> {
            try {
                return f.apply(x);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    /**
     * Converts the given function into a standard {@link Function}, wrapping any {@link IOException}
     * into {@link UncheckedIOException} with a message that includes the given {@link Context}.
     *
     * @param c the context for error reporting
     * @param f the function to convert
     * @return a {@link Function} that delegates to {@code f}
     */
    static <T, R> Function<T, R> io(final Context c, final IOThrowableFunction<T, R> f) {
        return x -> {
            try {
                return f.apply(x);
            } catch (final IOException e) {
                throw new UncheckedIOException("Exception raised " + c, e);
            }
        };
    }
}
