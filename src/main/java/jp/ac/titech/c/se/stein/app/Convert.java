package jp.ac.titech.c.se.stein.app;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

import jp.ac.titech.c.se.stein.core.HotEntry;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOCase;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import jp.ac.titech.c.se.stein.core.Context;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Slf4j
@Command(name = "convert", description = "Convert files via external HTTP API endpoint or command")
public class Convert implements BlobTranslator {
    @Option(names = "--endpoint", paramLabel = "<url>", description = "HTTP Web API endpoint")
    protected URL endpoint;

    @Option(names = "--cmd", split = " ", paramLabel = "<cmdline>", description = "Command with arguments")
    protected String[] cmdline;

    protected FileFilter filter;

    protected String[] patterns;

    @SuppressWarnings("unused")
    @Option(names = "--pattern", split = ";", paramLabel = "<glob;...>", description = "filter target files")
    protected void setPatterns(final String[] patterns) {
        this.patterns = patterns;
        this.filter = new WildcardFileFilter(patterns, caseSensitivity);
    }

    protected IOCase caseSensitivity = IOCase.SENSITIVE;

    @SuppressWarnings("unused")
    @Option(names = {"-i", "--ignore-case"}, description = "Perform case-insensitive matchinge")
    protected void setCaseInsensitive(final boolean isIgnoringCase) {
        caseSensitivity = isIgnoringCase ? IOCase.INSENSITIVE : IOCase.SENSITIVE;
        filter = new WildcardFileFilter(patterns, caseSensitivity);
    }

    @Option(names = "--exclude", description = "remove files that filtered out")
    protected boolean isExcluding;

    @Override
    public HotEntry rewriteBlobEntry(final HotEntry.SingleHotEntry entry, final Context c) {
        if (filter != null && !filter.accept(new File(entry.getName()))) {
            return isExcluding ? HotEntry.empty() : entry;
        }
        final byte[] blob = entry.getBlob();
        final byte[] converted = endpoint != null ? convertViaHttp(entry.getName(), blob, c) :
                                 cmdline != null  ? convertViaProcess(blob, c) :
                                 blob;
        return entry.update(converted);
    }

    protected byte[] convertViaProcess(final byte[] content, final Context c) {
        try {
            final Process proc = new ProcessBuilder()
                    .command(cmdline)
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
            log.error("IOException", e);
            return content;
        }
    }

    protected byte[] convertViaHttp(final String filename, final byte[] content, final Context c) {
        try {
            final HttpURLConnection conn = (HttpURLConnection) endpoint.openConnection();
            conn.setRequestMethod("POST");
            conn.setAllowUserInteraction(false);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-type", "text/plain");
            conn.setRequestProperty("Accept", "text/plain");
            conn.setRequestProperty("Content-Length", String.valueOf(content.length));
            conn.setRequestProperty("X-Filename", filename);
            conn.getOutputStream().write(content);
            if (conn.getResponseCode() == 200) {
                return IOUtils.toByteArray(conn.getInputStream());
            } else {
                log.error("Bad status code in response: {} {}", conn.getResponseCode(), c);
            }
        } catch (final IOException e) {
            log.error(e.getMessage(), e);
        }
        return content;
    }
}
