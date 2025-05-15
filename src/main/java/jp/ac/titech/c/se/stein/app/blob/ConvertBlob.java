package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Slf4j
@ToString
@Command(name = "@convert", description = "Convert blobs via command execution or Web API")
public class ConvertBlob implements BlobTranslator {

    @ArgGroup(multiplicity = "1")
    public ConvertOptions options;

    public static class ConvertOptions {
        @Option(names = "--cmd", split = " ", paramLabel = "<cmdline>", description = "Command with arguments",
                required = true)
        protected String[] cmdline;

        @Option(names = "--endpoint", paramLabel = "<url>", description = "HTTP Web API endpoint",
                required = true)
        protected URL endpoint;
    }

    @Mixin
    private final NameFilter filter = new NameFilter();

    @Override
    public AnyHotEntry rewriteBlobEntry(final HotEntry entry, final Context c) {
        if (!filter.accept(entry)) {
            return entry;
        }
        if (options.cmdline != null) {
            return entry.update(processCommandline(entry.getBlob(), c));
        } else {
            return entry.update(processEndpoint(entry.getName(), entry.getBlob(), c));
        }
    }

    protected byte[] processCommandline(final byte[] content, final Context c) {
        try {
            final Process proc = new ProcessBuilder()
                    .command(options.cmdline)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();

            final ExecutorService executor = Executors.newSingleThreadExecutor();
            final Future<ByteArrayOutputStream> future = executor.submit(() -> {
                try (final InputStream in = proc.getInputStream();
                     final ByteArrayOutputStream result = new ByteArrayOutputStream()) {
                    final byte[] buffer = new byte[4096];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        result.write(buffer, 0, len);
                    }
                    return result;
                }
            });

            try (final OutputStream out = proc.getOutputStream()) {
                out.write(content);
            }

            final int status = proc.waitFor();
            if (status != 0) {
                log.debug("Command {} exited with status {}", options.cmdline, status);
            }
            final byte[] result = future.get().toByteArray();
            executor.shutdown();
            return result;

        } catch (final IOException | InterruptedException | ExecutionException e) {
            log.error(e.getMessage(), e);
            return content;
        }
    }


    protected byte[] processEndpoint(final String filename, final byte[] content, final Context c) {
        try {
            final HttpURLConnection conn = (HttpURLConnection) options.endpoint.openConnection();
            conn.setRequestMethod("POST");
            conn.setAllowUserInteraction(false);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-type", "text/plain");
            conn.setRequestProperty("Accept", "text/plain");
            conn.setRequestProperty("Content-Length", String.valueOf(content.length));
            conn.setRequestProperty("X-Filename", filename);
            try (final OutputStream out = conn.getOutputStream()) {
                out.write(content);
            }
            if (conn.getResponseCode() == 200) {
                try (final InputStream in = conn.getInputStream()) {
                    in.readAllBytes();
                }
            } else {
                log.error("Bad status code in response: {} {} {}", conn.getResponseCode(), conn.getResponseMessage(), c);
            }
        } catch (final IOException e) {
            log.error(e.getMessage(), e);
        }
        return content;
    }
}
