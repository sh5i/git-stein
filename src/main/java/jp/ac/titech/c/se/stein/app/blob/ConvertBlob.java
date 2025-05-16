package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.Try;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import jp.ac.titech.c.se.stein.util.TemporaryFile;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@ToString
@Command(name = "@convert", description = "Convert blobs via command execution or Web API")
public class ConvertBlob implements BlobTranslator {

    @ArgGroup(multiplicity = "1")
    public ConvertOptions options;

    public static class ConvertOptions {
        @Option(names = "--cmd", paramLabel = "<cmdline>", description = "Command to run",
                required = true)
        protected String cmdline;

        @Option(names = "--endpoint", paramLabel = "<url>", description = "HTTP Web API endpoint",
                required = true)
        protected URL endpoint;
    }

    @Option(names = "--no-shell", negatable = true, description = "with[out] wrapped by /bin/sh -c")
    protected boolean requiresShell = true;

    @Option(names = "--filter", description = "Filter mode, eating stdin and dumping to stdout")
    protected boolean isFilter = false;

    @Mixin
    private final NameFilter filter = new NameFilter();

    protected String[] makeCommand(final String inputFile) {
        if (requiresShell) {
            return new String[]{ "/bin/sh", "-c", options.cmdline, inputFile };
        } else {
            return new String[] { options.cmdline, inputFile };
        }
    }

    @Override
    public AnyHotEntry rewriteBlobEntry(final HotEntry entry, final Context c) {
        if (!filter.accept(entry)) {
            return entry;
        }
        if (options.cmdline != null) {
            if (isFilter) {
                return processCommandlineFilter(entry, c);
            } else {
                return processCommandline(entry, c);
            }
        } else {
            return processEndpoint(entry, c);
        }
    }

    protected HotEntry processCommandlineFilter(final HotEntry entry, final Context c) {
        try {
            final Process proc = new ProcessBuilder()
                    .command(makeCommand(entry.getName()))
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
                out.write(entry.getBlob());
            }

            final int status = proc.waitFor();
            if (status != 0) {
                log.debug("Command {} exited with status {}", options.cmdline, status);
            }

            final ByteArrayOutputStream result = future.get();
            executor.shutdown();
            return entry.update(result.toByteArray());

        } catch (final IOException | InterruptedException | ExecutionException e) {
            log.error(e.getMessage(), e);
            return entry;
        }
    }

    protected AnyHotEntry processCommandline(final HotEntry entry, final Context c) {
        try (final TemporaryFile tmp = TemporaryFile.directoryOf("_stein")) {
            // write input
            final Path inputPath = tmp.getPath().resolve(entry.getName());
            Files.write(inputPath, entry.getBlob());

            // execute command
            final Process proc = new ProcessBuilder()
                    .command(makeCommand(entry.getName()))
                    .directory(tmp.getPath().toFile())
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            proc.getOutputStream().close();
            int status = proc.waitFor();
            if (status != 0) {
                log.debug("Command {} exited with status {}", options.cmdline, status);
            }

            // collect outputs
            try (final Stream<Path> files = Files.list(tmp.getPath())) {
                final List<HotEntry> entries = files.filter(Files::isRegularFile)
                        .map(Try.io(f -> HotEntry.of(entry.getMode(), f.getFileName().toString(), Files.readAllBytes(f))))
                        .collect(Collectors.toList());
                return AnyHotEntry.set(entries);
            }
        } catch (final InterruptedException | IOException e) {
            log.error(e.getMessage(), e);
            return entry;
        }
    }

    protected HotEntry processEndpoint(final HotEntry entry, final Context c) {
        try {
            final HttpURLConnection conn = (HttpURLConnection) options.endpoint.openConnection();
            conn.setRequestMethod("POST");
            conn.setAllowUserInteraction(false);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-type", "text/plain");
            conn.setRequestProperty("Accept", "text/plain");
            conn.setRequestProperty("Content-Length", String.valueOf(entry.getBlob().length));
            conn.setRequestProperty("X-Filename", entry.getName());
            try (final OutputStream out = conn.getOutputStream()) {
                out.write(entry.getBlob());
            }
            if (conn.getResponseCode() == 200) {
                try (final InputStream in = conn.getInputStream()) {
                    return entry.update(in.readAllBytes());
                }
            } else {
                log.error("Bad status code in response: {} {} {}", conn.getResponseCode(), conn.getResponseMessage(), c);
                return entry;
            }
        } catch (final IOException e) {
            log.error(e.getMessage(), e);
            return entry;
        }
    }
}
