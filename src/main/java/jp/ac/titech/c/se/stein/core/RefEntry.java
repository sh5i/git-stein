package jp.ac.titech.c.se.stein.core;

import java.io.Serializable;

import lombok.EqualsAndHashCode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;

/**
 * An immutable representation of a Git ref, either direct (pointing to an {@link ObjectId})
 * or symbolic (pointing to another ref by name).
 *
 * <p>A direct ref has a non-null {@link #id} and a null {@link #target}.
 * A symbolic ref has a non-null {@link #target} and a null {@link #id}.</p>
 */
@EqualsAndHashCode
public class RefEntry implements Serializable {
    /**
     * The ref name (e.g., {@code "refs/heads/main"} or {@code "HEAD"}).
     */
    public final String name;

    /**
     * The object ID for a direct ref, or {@code null} for a symbolic ref.
     */
    public final ObjectId id;

    /**
     * The target ref name for a symbolic ref, or {@code null} for a direct ref.
     */
    public final String target;

    /**
     * A sentinel instance representing an absent or deleted ref.
     */
    public static final RefEntry EMPTY = new RefEntry(null, null, null);

    private RefEntry(final String name, final ObjectId id, final String target) {
        this.name = name;
        // Copied via copy() to avoid retaining subclass instances (e.g., RevCommit)
        this.id = id != null ? id.copy() : null;
        this.target = target;
    }

    /**
     * Creates a direct ref.
     */
    public RefEntry(final String name, final ObjectId id) {
        this(name, id, null);
    }

    /**
     * Creates a symbolic ref.
     */
    public RefEntry(final String name, final String target) {
        this(name, null, target);
    }

    /**
     * Creates a {@link RefEntry} from a JGit {@link Ref}.
     */
    public RefEntry(final Ref ref) {
        this(ref.getName(),
             ref.isSymbolic() ? null : ref.getObjectId(),
             ref.isSymbolic() ? ref.getTarget().getName() : null);
    }

    /**
     * Returns {@code true} if this is a symbolic ref.
     */
    public boolean isSymbolic() {
        return target != null;
    }

    @Override
    public String toString() {
        return String.format("<Ref:%s %s>", name, target != null ? target : id.name());
    }
}
