package jp.ac.titech.c.se.stein.analyzer;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.SourceText;
import jp.ac.titech.c.se.stein.core.SourceText.Fragment;
import jp.ac.titech.c.se.stein.util.ProcessRunner;
import jp.ac.titech.c.se.stein.util.TemporaryFile;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import picocli.CommandLine.Option;

/**
 * The universal-ctags analyzer: it runs {@code ctags} (its own option) over the file and turns the
 * tag stream into a {@link Model}. It accepts any file; a consuming app narrows the files by
 * its own policy. {@code --ctags-kind} restricts the tags to the given ctags kinds; whether a module name
 * keeps the source extension is shared with the app's naming strategy.
 */
@Slf4j
public class CtagsAnalyzer implements Analyzer {
    @Getter
    @Setter
    @Option(names = "--ctags-cmd", description = "ctags command path")
    private String command = "ctags";

    /**
     * The ctags kinds to emit as modules; when unset, every kind is included.
     */
    @Option(names = "--ctags-kind", paramLabel = "<k>", description = "module kinds to include",
            arity = "0..*", split = ",")
    private Set<String> moduleKinds;

    /**
     * Whether a module file keeps the original source file's extension rather than a kind-derived
     * one.
     */
    @Getter
    @Option(names = "--ctags-original-ext", negatable = true,
            description = "keep original file extension in module names")
    private boolean requiresOriginalExtension = true;

    @Override
    public boolean accepts(final String filename) {
        return true;
    }

    @Override
    public SourceModel analyze(final String filename, final byte[] blob, final Context c) {
        final SourceText text = SourceText.ofNormalized(blob);
        final List<Model.LanguageObject> objects = runCtags(filename, text, c);
        return objects == null ? null : new Model(filename, text, objects, requiresOriginalExtension);
    }

    private List<Model.LanguageObject> runCtags(final String filename, final SourceText text, final Context c) {
        try (final TemporaryFile tmp = TemporaryFile.of("_stein", "." + filename)) {
            try (final FileOutputStream out = new FileOutputStream(tmp.getPath().toFile())) {
                out.write(text.getRaw());
            }
            return parseCtags(tmp.getPath(), c);
        } catch (final IOException e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    private List<Model.LanguageObject> parseCtags(final Path inputPath, final Context c) throws IOException {
        final String[] cmd = { command, "--output-format=json", "--fields=NnesKS", "-o", "-", inputPath.toString() };
        try (final ProcessRunner proc = new ProcessRunner(cmd, c)) {
            Stream<Model.LanguageObject> result = proc.getResultReader().lines()
                    .map(Model.LanguageObject::parse)
                    .filter(Model.LanguageObject::isValid);
            if (moduleKinds != null) {
                result = result.filter(lo -> moduleKinds.contains(lo.getKind()));
            }
            return result.sorted().collect(Collectors.toList());
        }
    }

    /**
     * A {@link SourceModel} backed by universal-ctags. It runs {@code ctags} on the file and turns the
     * flat stream of tags into an element tree: each distinct ctags scope (a dotted string) becomes one
     * naming-scope element under the file root, and every tag a leaf under its scope. A tag's ctags kind
     * (richer than the neutral {@link Element.Kind}) is carried as {@link Element#getRawKind}, and its
     * signature and line range as the element's {@link Signature} and line range; {@link #rawText} returns
     * the tag's source lines. Pair it with {@link jp.ac.titech.c.se.stein.app.blob.Historage.NamingStrategy.Ctags}.
     */
    public static class Model implements SourceModel {
        private final SourceText text;

        private final List<LanguageObject> objects;

        private final Element root;

        private boolean extracted;

        Model(final String filename, final SourceText text, final List<LanguageObject> objects,
                   final boolean requiresOriginalExtension) {
            this.text = text;
            this.objects = objects;
            final int index = filename.lastIndexOf('.');
            final String basename = requiresOriginalExtension && index > 0 ? filename.substring(0, index) : filename;
            this.root = new Element(Element.Kind.FILE, basename);
        }

        @Override
        public Element extract() {
            if (!extracted) {
                final Map<String, Element> scopes = new HashMap<>();
                for (final LanguageObject lo : objects) {
                    final Element parent = lo.scope == null ? root
                            : scopes.computeIfAbsent(lo.scope, s -> {
                                final Element scope = new Element(Element.Kind.CLASS, s);
                                root.addChild(scope);
                                return scope;
                            });
                    final Fragment fragment = text.getFragmentOfLines(lo.line, lo.end);
                    final Signature signature = lo.signature == null ? Signature.of(lo.name)
                            : new Signature(lo.name, null, List.of(normalize(lo.signature)));
                    final Element e = new Element(role(lo.kind), signature, fragment.getBegin(), fragment.getEnd());
                    e.setRawKind(lo.kind);
                    e.setStartLine(lo.line);
                    e.setEndLine(lo.end);
                    e.setCoreFragment(fragment);
                    e.setExtentFragment(fragment);
                    parent.addChild(e);
                }
                extracted = true;
            }
            return root;
        }

        /**
         * Collapses whitespace and strips the enclosing parentheses of a ctags signature, leaving the
         * comma normalization and digesting to the naming strategy.
         */
        private static String normalize(final String signature) {
            final String s = signature.replaceAll("\\s+", " ").trim();
            return s.startsWith("(") && s.endsWith(")") ? s.substring(1, s.length() - 1) : s;
        }

        /**
         * Maps a ctags kind to the neutral role used by the emit filter: method-like kinds to
         * {@link Element.Kind#METHOD}, type-like kinds to {@link Element.Kind#CLASS}, and the rest to
         * {@link Element.Kind#FIELD}. The original ctags kind is kept on the element for naming.
         */
        private static Element.Kind role(final String kind) {
            return switch (kind) {
                case "function", "method", "constructor", "destructor", "prototype", "subroutine", "operator" ->
                        Element.Kind.METHOD;
                case "class", "interface", "enum", "struct", "union", "namespace", "package", "module",
                     "trait", "annotation", "typedef", "record" ->
                        Element.Kind.CLASS;
                default -> Element.Kind.FIELD;
            };
        }

        /**
         * A parsed ctags tag: its name, kind, signature, and scope, plus its start and end line.
         */
        static final class LanguageObject implements Comparable<LanguageObject> {
            private static final Gson GSON = new Gson();

            private static final TypeToken<LanguageObject> TYPE_TOKEN = new TypeToken<>() {};

            @Getter
            private String name, kind, signature, scope;

            @Getter
            private int line, end;

            static LanguageObject parse(final String source) {
                return GSON.fromJson(source, TYPE_TOKEN.getType());
            }

            boolean isValid() {
                return name != null && line != 0 && end != 0;
            }

            private static final Comparator<LanguageObject> COMPARATOR = Comparator
                    .comparingInt(LanguageObject::getLine)
                    .thenComparing(LanguageObject::getEnd, Comparator.reverseOrder())
                    .thenComparing(LanguageObject::getScope, Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(LanguageObject::getKind, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(LanguageObject::getName, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(LanguageObject::getSignature, Comparator.nullsLast(Comparator.naturalOrder()));

            @Override
            public int compareTo(final LanguageObject other) {
                return COMPARATOR.compare(this, other);
            }
        }
    }
}
