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
        final byte[] result = options.cmdline != null
                ? processCommandline(entry.getBlob(), c)
                : processEndpoint(entry.getName(), entry.getBlob(), c);
        return entry.update(result);
    }

    protected byte[] processCommandline(final byte[] content, final Context c) {
        try {
            final Process proc = new ProcessBuilder()
                    .command(options.cmdline)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            try (final OutputStream out = proc.getOutputStream()) {
                out.write(content);
            }
            try (final InputStream in = proc.getInputStream()) {
                return in.readAllBytes();
            } finally {
                try (final BufferedReader err = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                    err.lines().forEach(line -> log.warn("stderr: {} {}", line, c));
                }
            }
        } catch (final IOException e) {
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
