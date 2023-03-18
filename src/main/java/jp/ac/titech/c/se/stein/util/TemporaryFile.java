package jp.ac.titech.c.se.stein.util;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
public class TemporaryFile implements AutoCloseable {
    @Getter
    private final Path path;

    public TemporaryFile(final String prefix, final String suffix) throws IOException {
        this.path = Files.createTempFile(prefix, suffix);
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(path);
    }
}
