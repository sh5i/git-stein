package jp.ac.titech.c.se.stein.app;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.*;
import jp.ac.titech.c.se.stein.core.EntrySet.Entry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Command;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * A Historage generator using universal-ctags.
 */
@Slf4j
@Command(name = "CtagsHistorage", description = "Generate finer-grained modules with ctags")
public class CtagsHistorage extends Extractor {
    public final static Path TMP = Paths.get("/tmp");

    @Override
    protected boolean accept(String filename) {
        return true;
    }

    @Override
    protected Collection<? extends Module> generate(final Entry entry, final SourceText text, final Context c) {
        return new CtagsRunner(entry.name, text, c).generate();
    }

    @RequiredArgsConstructor
    public static class CtagsRunner {
        private final String basename;

        private final SourceText text;

        private final Context c;

        private final Gson gson = new Gson();
        private final TypeToken<LanguageObject> t = new TypeToken<>() {};

        public Collection<Module> generate() {
            try {
                Path path = null;
                try {
                    path = createTemporaryFile();
                    return extractModules(path);
                } finally {
                    if (path != null) {
                        Files.delete(path);
                    }
                }
            } catch (final IOException e) {
                log.error("IOException: {} {}", e, c);
                return Collections.emptyList();
            }
        }

        protected Path createTemporaryFile() throws IOException {
            final Path result = Files.createTempFile(TMP, "_stein", "." + basename);
            try (FileOutputStream out = new FileOutputStream(result.toFile())) {
                out.write(text.getRaw());
            }
            return result;
        }

        protected List<Module> extractModules(final Path path) throws IOException {
            final List<LanguageObject> los = runCtags(path);
            if (los.isEmpty()) {
                return Collections.emptyList();
            }
            Collections.sort(los);
            text.prepareLineOffsets();

            final List<Module> result = new ArrayList<>();
            for (final LanguageObject lo : los) {
                final String name = generateName(lo);
                final String content = generateContent(lo);
                result.add(Module.of(name, content));
            }
            return result;
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
            final List<LanguageObject> result = new ArrayList<>();
            final String[] cmd = { "ctags", "--output-format=json", "--fields=NnesKS", "-o", "-", inputPath.toString() };
            final Process proc = Runtime.getRuntime().exec(cmd);
            try (final BufferedReader input = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                String line;
                while ((line = input.readLine()) != null) {
                    final LanguageObject lo = gson.fromJson(line, t.getType());
                    if (lo.name == null || lo.line == 0 || lo.end == 0) {
                        log.trace("Incompliant ctags entry: {} {}", line, c);
                        continue;
                    }
                    result.add(lo);
                }
            }
            try (final BufferedReader input = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
                String line;
                while ((line = input.readLine()) != null) {
                    log.warn("ctags stderr: {} {}", line, c);
                }
            }
            return result;
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

        @Override
        public int compareTo(final LanguageObject other) {
            int result = Integer.compare(line, other.line);
            if (result != 0) return result;
            result = Integer.compare(other.end, end);
            if (result != 0) return result;
            if (scope != null && other.scope != null) {
                result = scope.compareTo(other.scope);
                if (result != 0) return result;
            }
            if (kind != null && other.kind != null) {
                result = kind.compareTo(other.kind);
                if (result != 0) return result;
            }
            if (name != null && other.name != null) {
                result = name.compareTo(other.name);
                if (result != 0) return result;
            }
            if (signature != null && other.signature != null) {
                result = signature.compareTo(other.signature);
                if (result != 0) return result;
            }
            return 0;
        }
    }

    public static void main(final String[] args) throws IOException {
        Application.execute(new CtagsHistorage(), args);
    }
}
