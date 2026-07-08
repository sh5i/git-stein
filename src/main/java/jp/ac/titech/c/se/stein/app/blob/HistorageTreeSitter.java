package jp.ac.titech.c.se.stein.app.blob;

import java.util.ArrayList;
import java.util.List;

import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.entry.BlobEntry;
import jp.ac.titech.c.se.stein.entry.HotEntry;
import jp.ac.titech.c.se.stein.historage.FinerGitNaming;
import jp.ac.titech.c.se.stein.historage.Kind;
import jp.ac.titech.c.se.stein.historage.Module;
import jp.ac.titech.c.se.stein.historage.NamingStrategy;
import jp.ac.titech.c.se.stein.historage.ScopedNaming;
import jp.ac.titech.c.se.stein.ts.Element;
import jp.ac.titech.c.se.stein.ts.LanguageAnalyzer;
import jp.ac.titech.c.se.stein.ts.Languages;
import jp.ac.titech.c.se.stein.ts.RenderOptions;
import lombok.ToString;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * A Historage generator using tree-sitter, currently supporting Python, Java, C, C++, C#,
 * JavaScript, TypeScript, Go, Kotlin, Rust, Swift, and Ruby. Splits source files into finer-grained
 * modules (one file per class, function, or field). It maps the neutral {@link Element} tree that a
 * {@link Languages per-language analyzer} extracts to Historage modules: each element becomes a
 * module under its parent (naming scopes included, so names nest correctly), Java files following the
 * same FinerGit-compatible naming as {@link HistorageJdt} and the other languages a scoped naming of
 * their own.
 *
 * <p>Since tree-sitter parses in an error-tolerant way, modules are extracted even from files that
 * contain syntax errors elsewhere (e.g., historical Python 2 code).</p>
 */
@ToString
@Command(name = "@historage-ts", description = "Generate finer-grained modules via tree-sitter")
public class HistorageTreeSitter extends HistorageBase {
    @Option(names = "--no-classes", negatable = true, description = "[ex]/include class files")
    protected boolean requiresClasses = true;

    @Option(names = "--no-methods", negatable = true, description = "[ex]/include function files")
    protected boolean requiresMethods = true;

    @Option(names = "--no-fields", negatable = true, description = "[ex]/include field files")
    protected boolean requiresFields = true;

    @Override
    protected boolean accepts(final BlobEntry entry) {
        return Languages.accepts(entry.getName());
    }

    @Override
    protected List<? extends HotEntry> generateModules(final BlobEntry entry, final Context c) {
        final LanguageAnalyzer analyzer = Languages.of(entry.getName(), entry.getBlob());
        if (analyzer == null) {
            return List.of();
        }
        final Element root = analyzer.extract();
        final NamingStrategy naming = entry.getName().endsWith(".java")
                ? FinerGitNaming.INSTANCE : ScopedNaming.INSTANCE;
        final Module fileModule = Module.ofFile(root.getName(), naming);
        final List<Module> modules = new ArrayList<>();
        collect(analyzer, root, fileModule, naming, entry.getName(), modules);
        Module.resolveNameConflicts(modules);
        return modules.stream()
                .map(m -> HotEntry.of(entry.getMode(), m.getFilename(), m.getBlob()))
                .toList();
    }

    /**
     * Maps the analyzer's element tree to Historage modules. Every element becomes a module under its
     * parent so that names nest correctly (naming scopes included), but only those whose kind is
     * wanted and that carry renderable content are emitted as output files.
     */
    private void collect(final LanguageAnalyzer analyzer, final Element parent, final Module parentModule,
                         final NamingStrategy naming, final String filename, final List<Module> out) {
        for (final Element e : parent.getChildren()) {
            final Kind kind = switch (e.getKind()) {
                case CLASS -> Kind.CLASS;
                case METHOD -> Kind.METHOD;
                case FIELD -> Kind.FIELD;
                case FILE -> Kind.FILE;
            };
            final boolean emit = e.hasContent() && wants(kind);
            final String content = emit ? analyzer.render(e, renderOptions()) : null;
            final Module module = new Module(kind, e.getName(), parentModule, content, kind.extension(filename), naming);
            if (emit) {
                out.add(module);
            }
            collect(analyzer, e, module, naming, filename, out);
        }
    }

    /**
     * Whether an element of the given kind should be emitted as an output file. {@code @finer}
     * overrides {@link #wantsClasses} so classes stay naming scopes only.
     */
    private boolean wants(final Kind kind) {
        return switch (kind) {
            case CLASS -> wantsClasses();
            case METHOD -> requiresMethods;
            case FIELD -> requiresFields;
            case FILE -> false;
        };
    }

    protected boolean wantsClasses() {
        return requiresClasses;
    }

    /**
     * How module content is rendered; raw source by default. {@code @finer} overrides this to produce
     * FinerGit token sequences.
     */
    protected RenderOptions renderOptions() {
        return RenderOptions.RAW;
    }
}
