package jp.ac.titech.c.se.stein.app;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.eclipse.jgit.lib.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.EntrySet.Entry;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "Converter", description = "Convert files via HTTP API endpoint")
public class Converter extends RepositoryRewriter {
    private static final Logger log = LoggerFactory.getLogger(Converter.class);

    @Option(names = "--endpoint", paramLabel = "<url>", description = "HTTP Web API endpoint")
    protected URL endpoint;

    protected FileFilter filter;

    @SuppressWarnings("unused")
    @Option(names = "--pattern", paramLabel = "<glob>", description = "filter target files")
    protected void setFilter(final String glob) {
        filter = new WildcardFileFilter(glob);
    }

    @Option(names = "--exclude", description = "remove files that filtered out")
    protected boolean isExcluding;

    @Override
    protected ObjectId rewriteBlob(final ObjectId blobId, final Context c) {
        final Entry e = c.getEntry();
        if (filter != null && !filter.accept(new File(e.name))) {
            return isExcluding ? RepositoryRewriter.ZERO : super.rewriteBlob(blobId, c);
        }
        return target.writeBlob(convert(e.name, source.readBlob(blobId, c)), c);
    }

    protected byte[] convert(final String filename, final byte[] content) {
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
                log.error("Bad status code in response: {}", conn.getResponseCode());
            }
        } catch (final IOException e) {
            log.error(e.getMessage(), e);
        }
        return content;
    }

    public static void main(final String[] args) {
        Application.execute(new Converter(), args);
    }
}
