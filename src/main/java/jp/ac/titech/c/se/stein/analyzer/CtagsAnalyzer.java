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
import lombok.extern.slf4j.Slf4j;

/**
 * A {@link SourceAnalyzer} backed by universal-ctags. It runs {@code ctags} on the file and turns the
 * flat stream of tags into an element tree: each distinct ctags scope (a dotted string) becomes one
 * naming-scope element under the file root, and every tag a leaf under its scope. A tag's ctags kind
 * (richer than the neutral {@link Element.Kind}) is carried as {@link Element#getRawKind}, and its
 * signature and line range as the element's {@link Signature} and line range; {@link #rawText} returns
 * the tag's source lines. Pair it with {@link jp.ac.titech.c.se.stein.app.blob.Historage.NamingStrategy.Ctags}.
 */
@Slf4j
public class CtagsAnalyzer implements SourceAnalyzer {
    private final SourceText text;

    private final List<LanguageObject> objects;

    private final Element root;

    private boolean extracted;

    private CtagsAnalyzer(final String filename, final SourceText text, final List<LanguageObject> objects,
                          final boolean requiresOriginalExtension) {
        this.text = text;
        this.objects = objects;
        final int index = filename.lastIndexOf('.');
        final String basename = requiresOriginalExtension && index > 0 ? filename.substring(0, index) : filename;
        this.root = new Element(Element.Kind.FILE, basename);
    }

    /**
     * Runs ctags on the blob and returns a ready analyzer, or null when ctags fails. {@code moduleKinds}
     * (if non-null) restricts the tags to those ctags kinds; {@code requiresOriginalExtension} chooses
     * whether the file root's name keeps the source extension.
     */
    public static CtagsAnalyzer of(final String filename, final byte[] blob, final String ctags,
                                   final Set<String> moduleKinds, final boolean requiresOriginalExtension,
                                   final Context c) {
        final SourceText text = SourceText.ofNormalized(blob);
        final List<LanguageObject> objects = runCtags(filename, text, ctags, moduleKinds, c);
        return objects == null ? null : new CtagsAnalyzer(filename, text, objects, requiresOriginalExtension);
    }

    private static List<LanguageObject> runCtags(final String filename, final SourceText text, final String ctags,
                                                 final Set<String> moduleKinds, final Context c) {
        try (final TemporaryFile tmp = TemporaryFile.of("_stein", "." + filename)) {
            try (final FileOutputStream out = new FileOutputStream(tmp.getPath().toFile())) {
                out.write(text.getRaw());
            }
            return parseCtags(tmp.getPath(), ctags, moduleKinds, c);
        } catch (final IOException e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    private static List<LanguageObject> parseCtags(final Path inputPath, final String ctags,
                                                   final Set<String> moduleKinds, final Context c) throws IOException {
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
                e.setFragment(fragment);
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
