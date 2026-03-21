package jp.ac.titech.c.se.stein.util;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * An {@link AutoCloseable} wrapper for temporary files and directories.
 * The file or directory is deleted when {@link #close()} is called.
 */
@Slf4j
public abstract class TemporaryFile implements AutoCloseable {

    public abstract Path getPath();

    public abstract void close() throws IOException;

    /**
     * Creates a temporary file with the given prefix and suffix.
     */
    public static File of(final String prefix, final String suffix) throws IOException {
        return new File(prefix, suffix);
    }

    /**
     * Creates a temporary directory with the given prefix.
     */
    public static Directory directoryOf(final String prefix) throws IOException {
        return new Directory(prefix);
    }

    public static class File extends TemporaryFile {
        @Getter
        private final Path path;

        protected File(final String prefix, final String suffix) throws IOException {
            this.path = Files.createTempFile(prefix, suffix);
        }

        @Override
        public void close() throws IOException {
            Files.deleteIfExists(path);
        }
    }

    public static class Directory extends TemporaryFile {
        @Getter
        private final Path path;

        protected Directory(final String prefix) throws IOException {
            this.path = Files.createTempDirectory(prefix);
        }

        @Override
        public void close() throws IOException {
            FileUtils.deleteDirectory(path.toFile());
        }
    }
}
