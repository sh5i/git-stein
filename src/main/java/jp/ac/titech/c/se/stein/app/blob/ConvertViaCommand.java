package jp.ac.titech.c.se.stein.app.blob;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.*;

@Slf4j
@ToString
@Command(name = "@convert-cmd", description = "Convert files via command execution")
public class ConvertViaCommand implements BlobTranslator {
    @Option(names = "--cmd", split = " ", paramLabel = "<cmdline>", description = "Command with arguments",
            required = true)
    protected String[] cmdline;

    @Mixin
    private final NameFilter filter = new NameFilter();

    @Override
    public AnyHotEntry rewriteBlobEntry(final HotEntry entry, final Context c) {
        if (!filter.accept(entry)) {
            return entry;
        }
        return entry.update(convert(entry.getBlob(), c));
    }

    protected byte[] convert(final byte[] content, final Context c) {
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
            log.error(e.getMessage(), e);
            return content;
        }
    }
}
