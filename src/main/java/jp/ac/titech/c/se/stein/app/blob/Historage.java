package jp.ac.titech.c.se.stein.app.blob;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jp.ac.titech.c.se.stein.core.*;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.util.ProcessRunner;
import jp.ac.titech.c.se.stein.util.TemporaryFile;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A Historage generator using universal-ctags.
 */
@Slf4j
@ToString
@Command(name = "@historage", description = "Generate finer-grained modules via ctags")
public class Historage implements BlobTranslator {
    @Option(names = "--ctags", description = "ctags command used")
    protected String ctags = "ctags";

    @Option(names = "--no-original", negatable = true, description = "Exclude original files")
    protected boolean requiresOriginals = true;

    @Override
    public HotEntry rewriteBlobEntry(final HotEntry.Single entry, final Context c) {
        final HotEntry.Set result = HotEntry.set();
        if (requiresOriginals) {
            result.add(entry);
        }
        final SourceText text = SourceText.ofNormalized(entry.getBlob());
        try {
            final Collection<? extends HotEntry.Single> entries = new CtagsRunner(entry, text, c).generate();
            if (!entries.isEmpty()) {
                for (final HotEntry.Single e : entries) {
                    log.debug("Generate submodule: {} from {} {}", e.getName(), entry, c);
                    result.add(e);
                }
                log.debug("Rewrite entry: {} -> {} entries {}", entry, result.size(), c);
            }
        } catch (final IOException e) {
            log.error(e.getMessage(), e);
        }
        return result;
    }

    @RequiredArgsConstructor
    public class CtagsRunner {
        private final HotEntry.Single entry;

        private final SourceText text;

        private final Context c;

        private final Gson gson = new Gson();
        private final TypeToken<LanguageObject> token = new TypeToken<>() {};

        public Collection<HotEntry.Single> generate() throws IOException {
            try (final TemporaryFile tmp = new TemporaryFile("_stein", "." + entry.getName())) {
                try (final FileOutputStream out = new FileOutputStream(tmp.getPath().toFile())) {
                    out.write(text.getRaw());
                }
                return extractModules(tmp.getPath());
            }
        }

        protected List<HotEntry.Single> extractModules(final Path path) throws IOException {
            final List<LanguageObject> los = runCtags(path);
            if (!los.isEmpty()) {
                text.prepareLineOffsets();
            }
            return los.stream().sorted()
                    .map(lo -> HotEntry.of(entry.getMode(), generateName(lo), generateContent(lo)))
                    .collect(Collectors.toList());
        }

        protected String generateName(final LanguageObject lo) {
            final String scope = lo.scope != null ? "[" + lo.scope + "]" : "";
            //final String signature = m.signature != null ? "(~" + digest(m.signature, 4) + ")" : "";
            final String signature = "";
            return (entry.getName() + "!" + scope + lo.name + signature + "." + lo.kind).replace('/', '%');
        }

        protected byte[] generateContent(final LanguageObject lo) {
            return text.getFragmentOfLines(lo.line, lo.end).getWiderContent().getBytes(StandardCharsets.UTF_8);
        }

        protected List<LanguageObject> runCtags(final Path inputPath) throws IOException {
            final String[] cmd = { ctags, "--output-format=json", "--fields=NnesKS", "-o", "-", inputPath.toString() };
            try (final ProcessRunner proc = new ProcessRunner(cmd, c)) {
                return proc.getResult().lines().map(this::load).filter(this::isValid).collect(Collectors.toList());
            }
        }

        private LanguageObject load(final String line) {
            return gson.fromJson(line, token.getType());
        }

        private boolean isValid(final LanguageObject lo) {
            return lo.name != null && lo.line != 0 && lo.end != 0;
        }
    }

    /**
     * Parsed result of a language object of ctags.
     */
    @ToString
    public static class LanguageObject implements Comparable<LanguageObject> {
        @Getter
        protected String name, kind, signature, scope;

        @Getter
        protected int line, end;

        public static final Comparator<LanguageObject> COMPARATOR = Comparator
                .comparing(LanguageObject::getLine)
                .thenComparing(LanguageObject::getEnd, Comparator.reverseOrder())
                .thenComparing(LanguageObject::getScope, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(LanguageObject::getKind, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LanguageObject::getName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LanguageObject::getSignature, Comparator.nullsLast(Comparator.naturalOrder()));

        @Override
        public int compareTo(@Nonnull final LanguageObject other) {
            return COMPARATOR.compare(this, other);
        }
    }
}
