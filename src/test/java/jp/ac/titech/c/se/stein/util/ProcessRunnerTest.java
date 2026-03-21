package jp.ac.titech.c.se.stein.util;

import jp.ac.titech.c.se.stein.core.Context;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class ProcessRunnerTest {
    static final Context C = Context.init();

    @Test
    public void testIsAvailable() {
        // echo should be available on any system
        assertTrue(ProcessRunner.isAvailable("echo"));
        assertFalse(ProcessRunner.isAvailable("nonexistent_command_xyz"));
    }

    @Test
    public void testGetResult() throws IOException {
        try (ProcessRunner runner = new ProcessRunner(new String[]{"echo", "hello"}, C)) {
            final String output = new String(runner.getResult(), StandardCharsets.UTF_8).trim();
            assertEquals("hello", output);
        }
    }

    @Test
    public void testGetResultReader() throws IOException {
        try (ProcessRunner runner = new ProcessRunner(new String[]{"echo", "hello"}, C)) {
            final String line = runner.getResultReader().readLine();
            assertEquals("hello", line);
        }
    }

    @Test
    public void testWithStdinInput() throws IOException {
        try (ProcessRunner runner = new ProcessRunner(new String[]{"cat"}, "hello".getBytes(StandardCharsets.UTF_8), C)) {
            final String output = new String(runner.getResult(), StandardCharsets.UTF_8);
            assertEquals("hello", output);
        }
    }
}
