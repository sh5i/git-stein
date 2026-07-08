package jp.ac.titech.c.se.stein.app.blob;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import jp.ac.titech.c.se.stein.ts.ElementKind;
import jp.ac.titech.c.se.stein.ts.LanguageAnalyzer;
import jp.ac.titech.c.se.stein.ts.Languages;
import jp.ac.titech.c.se.stein.ts.Token;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

/**
 * Converts source files to cregit format using tree-sitter, as a srcML-free alternative to
 * {@link Cregit} that covers every language {@link Languages} supports. Each leaf token is written as
 * {@code type|content} on its own line; every class, method, and field is wrapped in a
 * {@code begin_}/{@code end_} pair, and the whole file in {@code begin_unit}/{@code end_unit}, so that
 * git tracks history at token granularity. The token type is derived from the grammar the same way the
 * FinerGit token sequence types it, so it differs from srcML's element names.
 */
@Slf4j
@ToString
@Command(name = "@cregit-ts", description = "cregit format via tree-sitter")
public class CregitTreeSitter implements BlobTranslator {
    public static final String CREGIT_VERSION = "0.0.1";

    @Option(names = "--position", description = "include line:column position in output")
    protected boolean position = false;

    @Mixin
    private final NameFilter filter = new NameFilter();

    @Override
    public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
        if (!filter.accept(entry)) {
            return entry;
        }
        final LanguageAnalyzer analyzer = Languages.of(entry.getName(), entry.getBlob());
        if (analyzer == null) {
            return entry;
        }
        log.debug("Generate cregit-ts token stream for {} {}", entry, c);
        return entry.update(convert(analyzer, Languages.nameOf(entry.getName())));
    }

    protected byte[] convert(final LanguageAnalyzer analyzer, final String language) {
        analyzer.extract();
        final StringBuilder sb = new StringBuilder();
        marker(sb, "begin_unit|language:" + language + ";cregit-version:" + CREGIT_VERSION);
        analyzer.walkTokens(new LanguageAnalyzer.TokenSink() {
            @Override
            public void begin(final ElementKind kind) {
                marker(sb, "begin_" + kind.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public void token(final Token t) {
                if (position) {
                    sb.append(t.line()).append(":").append(t.column()).append("|");
                }
                sb.append(t.type()).append("|").append(t.text()).append("\n");
            }

            @Override
            public void end(final ElementKind kind) {
                marker(sb, "end_" + kind.name().toLowerCase(Locale.ROOT));
            }
        });
        marker(sb, "end_unit");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private void marker(final StringBuilder sb, final String line) {
        if (position) {
            sb.append("-:-|");
        }
        sb.append(line).append("\n");
    }
}
