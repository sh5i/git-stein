package jp.ac.titech.c.se.stein.app.blob;

import com.sun.net.httpserver.HttpServer;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.util.ProcessRunner;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class ConvertBlobTest {
    static final Context C = Context.init();

    @Test
    public void testMakeCommandWithShell() {
        final ConvertBlob convert = new ConvertBlob();
        convert.options = new ConvertBlob.ConvertOptions();
        convert.options.cmdline = "wc -l";
        convert.requiresShell = true;

        final String[] cmd = convert.makeCommand("hello.txt");
        assertArrayEquals(new String[]{"/bin/sh", "-c", "wc -l", "hello.txt"}, cmd);
    }

    @Test
    public void testMakeCommandWithoutShell() {
        final ConvertBlob convert = new ConvertBlob();
        convert.options = new ConvertBlob.ConvertOptions();
        convert.options.cmdline = "wc";
        convert.requiresShell = false;

        final String[] cmd = convert.makeCommand("hello.txt");
        assertArrayEquals(new String[]{"wc", "hello.txt"}, cmd);
    }

    @Test
    public void testFilterMode() {
        assumeTrue(ProcessRunner.isAvailable("tr"), "tr not available");

        final ConvertBlob convert = new ConvertBlob();
        convert.options = new ConvertBlob.ConvertOptions();
        convert.options.cmdline = "tr a-z A-Z";
        convert.requiresShell = true;
        convert.isFilter = true;

        final BlobEntry entry = HotEntry.ofBlob("hello.txt", "hello");
        final AnyHotEntry result = convert.rewriteBlobEntry(entry, C);

        assertEquals(1, result.size());
        assertEquals("HELLO", result.asBlob().getContent());
    }

    @Test
    public void testEndpointMode() throws Exception {
        // start a simple HTTP server that echoes the body uppercased
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final int port = server.getAddress().getPort();
        server.createContext("/convert", exchange -> {
            final byte[] body = exchange.getRequestBody().readAllBytes();
            final byte[] response = new String(body, StandardCharsets.UTF_8)
                    .toUpperCase().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            final ConvertBlob convert = new ConvertBlob();
            convert.options = new ConvertBlob.ConvertOptions();
            convert.options.endpoint = new URL("http://127.0.0.1:" + port + "/convert");

            final BlobEntry entry = HotEntry.ofBlob("hello.txt", "hello");
            final AnyHotEntry result = convert.rewriteBlobEntry(entry, C);

            assertEquals(1, result.size());
            assertEquals("HELLO", result.asBlob().getContent());
        } finally {
            server.stop(0);
        }
    }
}
