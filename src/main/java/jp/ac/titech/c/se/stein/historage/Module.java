package jp.ac.titech.c.se.stein.historage;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jp.ac.titech.c.se.stein.util.HashUtils;
import lombok.Getter;
import lombok.Setter;

/**
 * A generated Historage module: a named region of a source file that becomes one output file.
 * Modules form a tree via their parent (up to the virtual {@link Kind#FILE} root), which the
 * {@link NamingStrategy} walks to build the file name.
 */
public class Module {
    /**
     * The maximum length of a single file name on common file systems.
     */
    private static final int MAX_FILENAME_BYTES = 255;

    @Getter
    protected final Kind kind;

    @Getter
    protected final String name;

    protected final Module parent;

    protected final String content;

    protected final String extension;

    protected final NamingStrategy naming;

    @Setter
    protected int index = 1;

    /**
     * The 1-based line range of this module in the source file, or -1 when not recorded.
     */
    @Getter
    @Setter
    protected int beginLine = -1;

    @Getter
    @Setter
    protected int endLine = -1;

    public Module(final Kind kind, final String name, final Module parent, final String content,
                  final String extension, final NamingStrategy naming) {
        this.kind = kind;
        this.name = name;
        this.parent = parent;
        this.content = content;
        this.extension = extension;
        this.naming = naming;
    }

    /**
     * A constructor for special modules that override {@link #getBasename} and thus need neither a
     * kind nor a naming strategy.
     */
    protected Module(final Module parent, final String content, final String extension) {
        this(null, null, parent, content, extension, null);
    }

    /**
     * Creates the virtual root module standing for the source file itself.
     */
    public static Module ofFile(final String name, final NamingStrategy naming) {
        return new Module(Kind.FILE, name, null, null, null, naming);
    }

    public String getBasename() {
        return naming.basename(this);
    }

    public String getFilename() {
        final String suffix = (index >= 2 ? "@" + index : "") + extension;
        final int budget = MAX_FILENAME_BYTES - suffix.getBytes(StandardCharsets.UTF_8).length;
        return HashUtils.abbreviateToBytes(getBasename(), budget) + suffix;
    }

    public byte[] getBlob() {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Appends {@code @2}, {@code @3}, ... to the second and later occurrences of the same file name.
     */
    public static void resolveNameConflicts(final List<? extends Module> modules) {
        final Map<String, Integer> counter = new HashMap<>();
        for (final Module m : modules) {
            final int count = counter.merge(m.getFilename(), 1, Integer::sum);
            if (count >= 2) {
                m.index = count;
            }
        }
    }
}
