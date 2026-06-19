package jp.ac.titech.c.se.stein.app.blob;

import com.google.gson.Gson;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import lombok.AllArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replaces secret text in text blobs, in the spirit of
 * <a href="https://rtyley.github.io/bfg-repo-cleaner/">BFG</a>'s {@code --replace-text}. Rules are a
 * JSON array of objects with a {@code pattern}, an optional {@code regex} flag (false means the
 * pattern is matched literally), and an optional {@code replacement} (default {@code ***REDACTED***}):
 *
 * <pre>
 * [
 *   {"pattern": "hunter2"},
 *   {"pattern": "token=\\w+", "regex": true, "replacement": "token=REDACTED"}
 * ]
 * </pre>
 *
 * Binary blobs are left untouched.
 */
@Slf4j
@ToString
@Command(name = "@redact", description = "Replace secret text in blobs")
public class Redact implements BlobTranslator {
    private static final String DEFAULT_REPLACEMENT = "***REDACTED***";

    /**
     * git's binary-detection threshold (xdiff-interface.c {@code FIRST_FEW_BYTES}): a blob is treated
     * as binary when a NUL byte occurs within this many leading bytes, matching {@code buffer_is_binary}.
     */
    private static final int BINARY_SNIFF_LENGTH = 8000;

    @Option(names = "--rules", paramLabel = "<file>", required = true, description = "JSON file of redaction rules")
    protected File rulesFile;

    @Mixin
    protected final NameFilter nameFilter = new NameFilter();

    private final List<Rule> rules = new ArrayList<>();

    @AllArgsConstructor
    private static class Rule {
        final Pattern pattern;
        final String replacement;
    }

    /**
     * A rule as written in the JSON file: a {@code pattern}, an optional {@code regex} flag (false
     * means literal), and an optional {@code replacement} (default {@code ***REDACTED***}).
     */
    private static class RuleSpec {
        String pattern;
        boolean regex;
        String replacement;
    }

    @Override
    public void setUp(final Context c) {
        try {
            final String json = Files.readString(rulesFile.toPath());
            for (final RuleSpec spec : new Gson().fromJson(json, RuleSpec[].class)) {
                final Pattern pattern = spec.regex
                        ? Pattern.compile(spec.pattern)
                        : Pattern.compile(Pattern.quote(spec.pattern));
                final String replacement = spec.replacement != null ? spec.replacement : DEFAULT_REPLACEMENT;
                // Quote once here so replaceAll treats the replacement literally (no $/\ expansion).
                rules.add(new Rule(pattern, Matcher.quoteReplacement(replacement)));
            }
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public AnyHotEntry rewriteBlobEntry(final BlobEntry entry, final Context c) {
        if (!nameFilter.accept(entry)) {
            return entry;
        }
        final byte[] blob = entry.getBlob();
        if (isBinary(blob)) {
            log.debug("skip binary {} {}", entry, c);
            return entry;
        }
        final String content = new String(blob, StandardCharsets.UTF_8);
        String updated = content;
        for (final Rule rule : rules) {
            updated = rule.pattern.matcher(updated).replaceAll(rule.replacement);
        }
        return updated.equals(content) ? entry : entry.update(updated);
    }

    private static boolean isBinary(final byte[] data) {
        final int n = Math.min(data.length, BINARY_SNIFF_LENGTH);
        for (int i = 0; i < n; i++) {
            if (data[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
