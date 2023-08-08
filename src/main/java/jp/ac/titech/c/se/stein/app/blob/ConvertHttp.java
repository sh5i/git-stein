package jp.ac.titech.c.se.stein.app.blob;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;

import jp.ac.titech.c.se.stein.core.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;

import jp.ac.titech.c.se.stein.core.Context;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Mixin;

@Slf4j
@ToString
@Command(name = "@convert-http", description = "Convert files via external HTTP API")
public class ConvertHttp implements BlobTranslator {
    @Option(names = "--endpoint", paramLabel = "<url>", description = "HTTP Web API endpoint",
            required = true)
    protected URL endpoint;

    @Mixin
    private final NameFilter filter = new NameFilter();

    @Override
    public HotEntry rewriteBlobEntry(final HotEntry.SingleHotEntry entry, final Context c) {
        if (!filter.accept(entry)) {
            return entry;
        }
        final byte[] blob = entry.getBlob();
        final byte[] converted = convert(entry.getName(), blob, c);
        return entry.update(converted);
    }

    protected byte[] convert(final String filename, final byte[] content, final Context c) {
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
