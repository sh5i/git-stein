package jp.ac.titech.c.se.stein.core;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;

/**
 * A tiny helper for converting IOException to UncheckedIOException.
 */
public interface Try {
    @FunctionalInterface
    interface ThrowableRunnable {
        void run() throws Exception;
    }

    @FunctionalInterface
    interface ThrowableSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    interface ThrowableFunction<T, R> {
        R apply(T t) throws Exception;
    }

    // ------

    @FunctionalInterface
    interface IOThrowableRunnable {
        void run() throws IOException;
    }

    @FunctionalInterface
    interface IOThrowableSupplier<T> {
        T get() throws IOException;
    }

    @FunctionalInterface
    interface IOThrowableFunction<T, R> {
        R apply(T t) throws IOException;
    }

    // ------

    static void run(final ThrowableRunnable f) {
        try {
            f.run();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void run(final Context c, final ThrowableRunnable f) {
        try {
            f.run();
        } catch (final Exception e) {
            throw new RuntimeException("Exception raised (" + c + ")", e);
        }
    }

    static <T> T run(final ThrowableSupplier<T> f) {
        try {
            return f.get();
        } catch (final Exception e) {
            throw new RuntimeException(e);
        }
    }

    static <T> T run(final Context c, final ThrowableSupplier<T> f) {
        try {
            return f.get();
        } catch (final Exception e) {
            throw new RuntimeException("Exception raised (" + c + ")", e);
        }
    }

    static <T, R> Function<T, R> run(final ThrowableFunction<T, R> f) {
        return x -> {
            try {
                return f.apply(x);
            } catch (final Exception e) {
                throw new RuntimeException(e);
            }
        };
    }

    static <T, R> Function<T, R> run(final Context c, final ThrowableFunction<T, R> f) {
        return x -> {
            try {
                return f.apply(x);
            } catch (final Exception e) {
                throw new RuntimeException("Exception raised (" + c + ")", e);
            }
        };
    }

    // ------

    static void io(final IOThrowableRunnable f) {
        try {
            f.run();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static void io(final Context c, final IOThrowableRunnable f) {
        try {
            f.run();
        } catch (final IOException e) {
            throw new UncheckedIOException("Exception raised (" + c + ")", e);
        }
    }

    static <T> T io(final IOThrowableSupplier<T> f) {
        try {
            return f.get();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static <T> T io(final Context c, final IOThrowableSupplier<T> f) {
        try {
            return f.get();
        } catch (final IOException e) {
            throw new UncheckedIOException("Exception raised (" + c + ")", e);
        }
    }

    static <T, R> Function<T, R> io(final IOThrowableFunction<T, R> f) {
        return x -> {
            try {
                return f.apply(x);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        };
    }

    static <T, R> Function<T, R> io(final Context c, final IOThrowableFunction<T, R> f) {
        return x -> {
            try {
                return f.apply(x);
            } catch (final IOException e) {
                throw new UncheckedIOException("Exception raised (" + c + ")", e);
            }
        };
    }
}
