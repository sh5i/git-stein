package jp.ac.titech.c.se.stein.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

public class TryTest {
    final Context c = Context.init().with(Context.Key.path, "test");

    @Test
    public void testRunRunnable() {
        assertDoesNotThrow(() -> Try.run(() -> {}));
        assertDoesNotThrow(() -> Try.run(c, () -> {}));

        final RuntimeException e1 = assertThrows(RuntimeException.class, () -> {
            Try.run(() -> { throw new Exception("fail"); });
        });
        assertEquals("fail", e1.getCause().getMessage());

        final RuntimeException e2 = assertThrows(RuntimeException.class, () -> {
            Try.run(c, () -> { throw new Exception("fail"); });
        });
        assertEquals("Exception raised (path: \"test\")", e2.getMessage());
        assertEquals("fail", e2.getCause().getMessage());
    }

    @Test
    public void testRunSupplier() {
        assertEquals("hello", Try.run(() -> "hello"));
        assertEquals("hello", Try.run(c, () -> "hello"));

        final RuntimeException e1 = assertThrows(RuntimeException.class, () -> {
            Try.<String>run(() -> { throw new Exception("fail"); });
        });
        assertEquals("fail", e1.getCause().getMessage());

        final RuntimeException e2 = assertThrows(RuntimeException.class, () -> {
            Try.<String>run(c, () -> { throw new Exception("fail"); });
        });
        assertEquals("Exception raised (path: \"test\")", e2.getMessage());
        assertEquals("fail", e2.getCause().getMessage());
    }

    @Test
    public void testRunFunction() {
        final Function<String, Integer> f1 = Try.run(String::length);
        assertEquals(5, f1.apply("hello"));

        final Function<String, Integer> f2 = Try.run(c, String::length);
        assertEquals(5, f2.apply("hello"));

        final Function<String, Integer> g1 = Try.run(s -> { throw new Exception("fail"); });
        assertThrows(RuntimeException.class, () -> g1.apply("hello"));

        final Function<String, Integer> g2 = Try.run(c, s -> { throw new Exception("fail"); });
        final RuntimeException e = assertThrows(RuntimeException.class, () -> g2.apply("hello"));
        assertEquals("Exception raised (path: \"test\")", e.getMessage());
        assertEquals("fail", e.getCause().getMessage());
    }

    @Test
    public void testIoRunnable() {
        assertDoesNotThrow(() -> Try.io(() -> {}));
        assertDoesNotThrow(() -> Try.io(c, () -> {}));

        final UncheckedIOException e1 = assertThrows(UncheckedIOException.class, () -> {
            Try.io(() -> { throw new IOException("fail"); });
        });
        assertEquals("fail", e1.getCause().getMessage());

        final UncheckedIOException e2 = assertThrows(UncheckedIOException.class, () -> {
            Try.io(c, () -> { throw new IOException("fail"); });
        });
        assertEquals("Exception raised (path: \"test\")", e2.getMessage());
        assertEquals("fail", e2.getCause().getMessage());
    }

    @Test
    public void testIoSupplier() {
        assertEquals("hello", Try.io(() -> "hello"));
        assertEquals("hello", Try.io(c, () -> "hello"));

        final UncheckedIOException e1 = assertThrows(UncheckedIOException.class, () -> {
            Try.<String>io(() -> { throw new IOException("fail"); });
        });
        assertEquals("fail", e1.getCause().getMessage());

        final UncheckedIOException e2 = assertThrows(UncheckedIOException.class, () -> {
            Try.<String>io(c, () -> { throw new IOException("fail"); });
        });
        assertEquals("Exception raised (path: \"test\")", e2.getMessage());
        assertEquals("fail", e2.getCause().getMessage());
    }

    @Test
    public void testIoFunction() {
        final Function<String, Integer> f1 = Try.io(String::length);
        assertEquals(5, f1.apply("hello"));

        final Function<String, Integer> f2 = Try.io(c, String::length);
        assertEquals(5, f2.apply("hello"));

        final Function<String, Integer> g1 = Try.io(s -> { throw new IOException("fail"); });
        assertThrows(UncheckedIOException.class, () -> g1.apply("hello"));

        final Function<String, Integer> g2 = Try.io(c, s -> { throw new IOException("fail"); });
        final UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> g2.apply("hello"));
        assertEquals("Exception raised (path: \"test\")", e.getMessage());
        assertEquals("fail", e.getCause().getMessage());
    }
}
