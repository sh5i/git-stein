package jp.ac.titech.c.se.stein.util;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class TemporaryFileTest {
    @Test
    public void testFile() throws IOException {
        final Path path;
        try (TemporaryFile.File tmp = TemporaryFile.of("_test", ".txt")) {
            path = tmp.getPath();
            assertTrue(Files.exists(path));
            Files.writeString(path, "hello");
            assertEquals("hello", Files.readString(path));
        }
        assertFalse(Files.exists(path));
    }

    @Test
    public void testDirectory() throws IOException {
        final Path path;
        try (TemporaryFile.Directory tmp = TemporaryFile.directoryOf("_test")) {
            path = tmp.getPath();
            assertTrue(Files.isDirectory(path));

            // create a file inside
            final Path child = path.resolve("hello.txt");
            Files.writeString(child, "hello");
            assertTrue(Files.exists(child));
        }
        // directory and contents should be deleted
        assertFalse(Files.exists(path));
    }
}
