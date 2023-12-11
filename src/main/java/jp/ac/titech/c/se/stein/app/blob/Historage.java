package jp.ac.titech.c.se.stein.app.blob;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jp.ac.titech.c.se.stein.core.*;
import jp.ac.titech.c.se.stein.entry.AnyHotEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.NameFilter;
import jp.ac.titech.c.se.stein.util.HashUtils;
import jp.ac.titech.c.se.stein.util.ProcessRunner;
import jp.ac.titech.c.se.stein.util.TemporaryFile;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Mixin;

import javax.annotation.Nonnull;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A Historage generator using universal-ctags.
 */
@Slf4j
@ToString
@Command(name = "@historage", description = "Generate finer-grained modules via ctags")
public class Historage implements BlobTranslator {
    @Option(names = "--ctags", description = "ctags command used")
    protected String ctags = "ctags";

    @Option(names = "--no-original", negatable = true, description = "exclude original files")
    protected boolean requiresOriginals = true;

    @Option(names = "--no-original-ext", negatable = true, description = "disuse original file extension")
    protected boolean requiresOriginalExtension = true;

    @Option(names = "--no-sig", negatable = true, description = "stop using signature")
    protected boolean useSignature = true;

    @Option(names = "--no-digest-sig", negatable = true, description = "stop digesting signature")
    protected boolean digestSignature = true;

    @Mixin
    private final NameFilter filter = new NameFilter();

    @Option(names = "--kind", paramLabel = "<k>", description = "specify module kinds to include",
            arity = "0..*", split = ",")
    protected Set<String> moduleKinds;

    @Override
    public AnyHotEntry rewriteBlobEntry(final HotEntry entry, final Context c) {
        if (!filter.accept(entry)) {
            return entry;
        }
        final AnyHotEntry.Set result = AnyHotEntry.set();
        if (requiresOriginals) {
            result.add(entry);
        }
        final SourceText text = SourceText.ofNormalized(entry.getBlob());
        try {
            final Collection<? extends HotEntry> entries = new CtagsRunner(entry, text, c).generate();
            if (!entries.isEmpty()) {
                for (final HotEntry e : entries) {
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
        private final HotEntry entry;

        private final SourceText text;

        private final Context c;

        public List<HotEntry> generate() throws IOException {
            try (final TemporaryFile tmp = new TemporaryFile("_stein", "." + entry.getName())) {
                try (final FileOutputStream out = new FileOutputStream(tmp.getPath().toFile())) {
                    out.write(text.getRaw());
                }
                return extractModules(tmp.getPath());
            }
        }

        protected List<HotEntry> extractModules(final Path path) throws IOException {
            final List<LanguageObject> los = runCtags(path);
            resolveNameConflicts(los);
            return los.stream()
                    .map(lo -> HotEntry.of(entry.getMode(), generateName(lo), generateContent(lo)))
                    .collect(Collectors.toList());
        }

        /**
         * Similar to RepositoryAccess#resolveNameConflicts, but a bit simpler.
         */
        protected void resolveNameConflicts(final List<LanguageObject> los) {
            final Map<String, Integer> counter = new HashMap<>();
            for (final LanguageObject lo : los) {
                final String name = lo.generateFileName();
                if (counter.containsKey(name)) {
                    lo.index = counter.get(name) + 1;
                    counter.put(name, lo.index);
                } else {
                    counter.put(name, 1);
                }
            }
        }

        protected String generateName(final LanguageObject lo) {
            final String moduleName = lo.generateFileName();
            if (requiresOriginalExtension) {
                final String name = entry.getName();
                final int index = name.lastIndexOf('.');
                final String basename = index > 0 ? name.substring(0, index) : name;
                final String ext = index > 0 ? name.substring(index) : "";
                return basename + "!" + moduleName + ext;
            } else {
                return entry.getName() + "!" + moduleName;
            }
        }

        protected byte[] generateContent(final LanguageObject lo) {
            return text.getFragmentOfLines(lo.line, lo.end).getWiderContent().getBytes(StandardCharsets.UTF_8);
        }

        protected List<LanguageObject> runCtags(final Path inputPath) throws IOException {
            final String[] cmd = { ctags, "--output-format=json", "--fields=NnesKS", "-o", "-", inputPath.toString() };
            try (final ProcessRunner proc = new ProcessRunner(cmd, c)) {
                Stream<LanguageObject> result = proc.getResultReader().lines()
                        .map(LanguageObject::parse)
                        .filter(LanguageObject::isValid);
                if (moduleKinds != null) {
                    result = result.filter(lo -> moduleKinds.contains(lo.kind));
                }
                return result.sorted().collect(Collectors.toList());
            }
        }
    }

    /**
     * Parsed result of a language object of ctags.
     */
    @ToString
    public static class LanguageObject implements Comparable<LanguageObject> {
        protected static final Gson GSON = new Gson();
        protected static final TypeToken<LanguageObject> TYPE_TOKEN = new TypeToken<>() {};

        @Getter
        protected String name, kind, signature, scope;

        @Getter
        protected int line, end;

        @Getter
        protected int index = 1;

        public static LanguageObject parse(final String source) {
            return GSON.fromJson(source, TYPE_TOKEN.getType());
        }

        public boolean isValid() {
            return name != null && line != 0 && end != 0;
        }

        public String generateFileName() {
            final StringBuilder sb = new StringBuilder();
            if (scope != null) {
                sb.append(scope.replace('/', '%')).append("$");
            }
            sb.append(name);
            if (signature != null) {
                sb.append("(~").append(HashUtils.digest(signature, 4)).append(")");
            }
            if (index >= 2) {
                sb.append("@").append(index);
            }
            sb.append(".").append(kind.replace('/', '%'));
            return sb.toString();
        }

        public static final Comparator<LanguageObject> COMPARATOR = Comparator
                .comparingInt(LanguageObject::getLine)
                .thenComparing(LanguageObject::getEnd, Comparator.reverseOrder())
                .thenComparing(LanguageObject::getScope, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(LanguageObject::getKind, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LanguageObject::getName, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LanguageObject::getSignature, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LanguageObject::getIndex);

        @Override
        public int compareTo(@Nonnull final LanguageObject other) {
            return COMPARATOR.compare(this, other);
        }
    }
}
