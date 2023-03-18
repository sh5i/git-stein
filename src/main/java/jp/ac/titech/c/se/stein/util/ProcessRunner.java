package jp.ac.titech.c.se.stein.util;

import jp.ac.titech.c.se.stein.core.Context;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

@Slf4j
public class ProcessRunner implements AutoCloseable {
    @Getter
    private final Process proc;

    @Getter
    private final BufferedReader result;

    private final Context c;

    public ProcessRunner(final String[] cmdline, final Context c) throws IOException {
        this.proc = new ProcessBuilder()
                .command(cmdline)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        this.result = new BufferedReader(new InputStreamReader(proc.getInputStream()));
        this.c = c;
    }

    @Override
    public void close() throws IOException {
        try (final BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
            err.lines().forEach(line -> log.warn("stderr: {} {}", line, c));
        }
        result.close();
    }
}
