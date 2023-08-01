package jp.ac.titech.c.se.stein.app;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jp.ac.titech.c.se.stein.core.*;
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
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A Historage generator using universal-ctags.
 */
@Slf4j
@Command(name = "historage", aliases = "historinc", description = "Generate finer-grained modules")
public class Historage extends Extractor {
    @Option(names = "--ctags", description = "ctags command used")
    protected String ctags = "ctags";

    @Override
    protected boolean accept(String filename) {
        return true;
    }

    @Override
    protected Collection<? extends Module> generate(final HotEntry.SingleHotEntry entry, final SourceText text, final Context c) {
        try {
            return new CtagsRunner(entry.getName(), text, c).generate();
        } catch (final IOException e) {
            log.error("IOException: {} {}", e, c);
            return Collections.emptyList();
        }
    }

    @RequiredArgsConstructor
    public class CtagsRunner {
        private final String basename;

        private final SourceText text;

        private final Context c;

        private final Gson gson = new Gson();
        private final TypeToken<LanguageObject> token = new TypeToken<>() {};

        public Collection<Module> generate() throws IOException {
            try (final TemporaryFile tmp = new TemporaryFile("_stein", "." + basename)) {
                try (final FileOutputStream out = new FileOutputStream(tmp.getPath().toFile())) {
                    out.write(text.getRaw());
                }
                return extractModules(tmp.getPath());
            }
        }

        protected List<Module> extractModules(final Path path) throws IOException {
            final List<LanguageObject> los = runCtags(path);
            if (!los.isEmpty()) {
                text.prepareLineOffsets();
            }
            return los.stream().sorted().map(this::convert).collect(Collectors.toList());
        }

        protected Module convert(final LanguageObject lo) {
            return Module.of(generateName(lo), generateContent(lo));
        }

        protected String generateName(final LanguageObject lo) {
            final String scope = lo.scope != null ? "[" + lo.scope + "]" : "";
            //final String signature = m.signature != null ? "(~" + digest(m.signature, 4) + ")" : "";
            final String signature = "";
            return (basename + "!" + scope + lo.name + signature + "." + lo.kind).replace('/', '%');
        }

        protected String generateContent(final LanguageObject lo) {
            return text.getFragmentOfLines(lo.line, lo.end).getWiderContent();
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
                .thenComparing(LanguageObject::getScope)
                .thenComparing(LanguageObject::getKind)
                .thenComparing(LanguageObject::getName)
                .thenComparing(LanguageObject::getSignature);

        @Override
        public int compareTo(@Nonnull final LanguageObject other) {
            return COMPARATOR.compare(this, other);
        }
    }
}
