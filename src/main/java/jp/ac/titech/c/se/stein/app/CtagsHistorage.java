package jp.ac.titech.c.se.stein.app;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.EntrySet;
import jp.ac.titech.c.se.stein.core.EntrySet.Entry;
import jp.ac.titech.c.se.stein.core.EntrySet.EntryList;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import jp.ac.titech.c.se.stein.core.Try;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.util.sha1.SHA1;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * A Historage generator using universal-ctags.
 */
@Slf4j
@Command(name = "CtagsHistorage", description = "Generate finer-grained modules with ctags")
public class CtagsHistorage extends RepositoryRewriter {
    public final static Path TMP = Paths.get("/tmp");

    @Option(names = "--no-original", negatable = true, description = "[ex]/include original files")
    protected boolean requiresOriginals = true;

    @Override
    public EntrySet rewriteEntry(final Entry entry, final Context c) {
        if (!entry.isFile()) {
            return super.rewriteEntry(entry, c);
        }

        final EntryList result = new EntryList();
        if (requiresOriginals) {
            result.add((Entry) super.rewriteEntry(entry, c));
        }

        final byte[] blob = source.readBlob(entry.id, c);
        final List<Module> modules = collectFinerModules(blob, entry.name, c);
        if (!modules.isEmpty()) {
            final Source source = new Source(blob);
            for (final Module m : modules) {
                final String content = source.getFragment(m.line, m.end);
                final ObjectId newId = target.writeBlob(content.getBytes(StandardCharsets.UTF_8), c);
                final String newName = generateName(entry.name, m);
                log.debug("Generate module: {} [{}] from {} {}", newName, newId.name(), entry, c);
                result.add(new Entry(entry.mode, newName, newId, entry.directory));
            }
            log.debug("Rewrite entry: {} -> %d entries {} {}", entry, result.size(), c);
        }
        return result;
    }

    public String generateName(final String base, final Module m) {
        final String scope = m.scope != null ? "[" + m.scope + "]" : "";
        //final String signature = m.signature != null ? "(~" + digest(m.signature, 4) + ")" : "";
        final String signature = "";
        return (base + "!" + scope + m.name + signature + "." + m.kind).replace('/', '%');
    }

    protected List<Module> collectFinerModules(final byte[] blob, final String filename, final Context c) {
        try {
            Path path = null;
            try {
                path = createTemporaryFile(blob, filename);
                return runCtags(path.toString(), c);
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

    protected Path createTemporaryFile(final byte[] blob, final String filename) throws IOException {
        final Path result = Files.createTempFile(TMP, "_stein", "." + filename);
        try (FileOutputStream out = new FileOutputStream(result.toFile())) {
            out.write(blob);
        }
        return result;
    }

    protected List<Module> runCtags(final String file, final Context c) throws IOException {
        final List<Module> result = new ArrayList<>();
        final String[] cmd = { "ctags", "--output-format=json", "--fields=NnesKS", "-o", "-", file };
        final Process proc = Runtime.getRuntime().exec(cmd);
        try (final BufferedReader input = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = input.readLine()) != null) {
                final Module m = parseJsonLine(line);
                if (m.name == null || m.line == 0 || m.end == 0) {
                    log.trace("Incompliant ctags entry: {} {}", line, c);
                    continue;
                }
                result.add(m);
            }
        }
        try (final BufferedReader input = new BufferedReader(new InputStreamReader(proc.getErrorStream()))) {
            String line;
            while ((line = input.readLine()) != null) {
                log.warn("ctags stderr: {} {}", line, c);
            }
        }
        Collections.sort(result);
        return result;
    }

    protected Module parseJsonLine(final String line) {
        final Gson gson = new Gson();
        final TypeToken<Module> t = new TypeToken<>() {};
        return Try.run(() -> gson.fromJson(line, t.getType()));
    }

    public static class Module implements Comparable<Module> {
        protected String name;
        protected String kind;
        protected String signature;
        protected String scope;
        protected int line;
        protected int end;

        @Override
        public String toString() {
            return (scope != null ? "[" + scope + "]" : "") + name + "." + kind + "@" + line + "," + end;
        }

        @Override
        public int compareTo(final Module other) {
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

    public static class Source {
        protected final String content;

        protected final byte[] raw;

        protected int[] offsets;

        public Source(final byte[] raw) {
            this.raw = raw;
            this.content = normalize(raw);
            this.offsets = computeLineOffsets(this.content);
        }

        protected static String normalize(final byte[] blob) {
            final String content = new String(blob, StandardCharsets.UTF_8).replaceAll("\r\n?", "\n");
            return content.charAt(content.length() - 1) == '\n' ? content : (content + "\n");
        }

        protected static int[] computeLineOffsets(final String source) {
            final Pattern pattern = Pattern.compile("\n");
            final Matcher matcher = pattern.matcher(source);
            return IntStream.concat(IntStream.of(0), matcher.results().mapToInt(m -> m.start() + 1)).toArray();
        }

        public String getFragment(final int beginLine, final int endLine) {
            int beginIndex = offsets[beginLine - 1];
            int endIndex = endLine < offsets.length ? offsets[endLine] : content.length();
            return content.substring(beginIndex, endIndex);
        }
    }

    protected static String digest(final String name, @SuppressWarnings("SameParameterValue") final int length) {
        final SHA1 sha1 = SHA1.newInstance();
        sha1.update(name.getBytes());
        return ObjectId.fromRaw(sha1.digest()).abbreviate(length).name();
    }

    public static void main(final String[] args) throws IOException {
        Application.execute(new CtagsHistorage(), args);
    }
}
