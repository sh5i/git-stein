package jp.ac.titech.c.se.stein.util;

import jp.ac.titech.c.se.stein.core.Context;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.*;

/**
 * Runs an external process and provides access to its output.
 */
@Slf4j
public class ProcessRunner implements AutoCloseable {
    /**
     * Returns {@code true} if the given command is available on the system PATH.
     */
    public static boolean isAvailable(String command) {
        try {
            final Process p = new ProcessBuilder(command, "--version")
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Getter
    private final Process proc;

    private BufferedReader reader;

    private final Context c;

    public ProcessRunner(final String[] cmdline, final Context c) throws IOException {
        this.proc = new ProcessBuilder()
                .command(cmdline)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        this.c = c;
    }

    public ProcessRunner(final String[] cmdline, final byte[] input, final Context c) throws IOException {
        this.proc = new ProcessBuilder()
                .command(cmdline)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        // FIXME: does not work if the target command blocks
        try (final OutputStream out = proc.getOutputStream()) {
            out.write(input);
        }
        this.c = c;
    }

    public BufferedReader getResultReader() {
        reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        return reader;
    }

    public byte[] getResult() {
        try (final InputStream in = proc.getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        try (final BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
            err.lines().forEach(line -> log.warn("stderr: {} {}", line, c));
        }
        if (reader != null) {
            reader.close();
        }
    }
}
