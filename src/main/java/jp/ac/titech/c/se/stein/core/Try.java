package jp.ac.titech.c.se.stein.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;

/**
 * A tiny helper for converting IOException to UncheckedIOException.
 */
public class Try {
    @FunctionalInterface
    public static interface IOThrowableRunnable {
        void run() throws IOException;
    }

    @FunctionalInterface
    public static interface IOThrowableSupplier<T> {
        T get() throws IOException;
    }

    @FunctionalInterface
    public static interface IOThrowableFunction<T, R> {
        R apply(T t) throws IOException;
    }

    public static void io(final IOThrowableRunnable f) {
        try {
            f.run();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static void io(final Context c, final IOThrowableRunnable f) {
        try {
            f.run();
        } catch (final IOException e) {
            throw new UncheckedIOException("Exception raised (context: " + c + ")", e);
        }
    }

    public static <T> T io(final IOThrowableSupplier<T> f) {
        try {
            return f.get();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static <T> T io(final Context c, final IOThrowableSupplier<T> f) {
        try {
            return f.get();
        } catch (final IOException e) {
            throw new UncheckedIOException("Exception raised (context: " + c + ")", e);
        }
    }

    public static <T, R> Function<T, R> io(final IOThrowableFunction<T, R> f) {
        return (x) -> {
            try {
                return f.apply(x);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    public static <T, R> Function<T, R> io(final Context c, final IOThrowableFunction<T, R> f) {
        return (x) -> {
            try {
                return f.apply(x);
            } catch (final IOException e) {
                throw new UncheckedIOException("Exception raised (context: " + c + ")", e);
            }
        };
    }
}
