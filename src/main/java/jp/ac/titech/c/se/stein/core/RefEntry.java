package jp.ac.titech.c.se.stein.core;

import java.io.Serializable;
import java.util.Objects;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;

/**
 * A ref entry.
 */
public class RefEntry implements Serializable {
    public final String name;

    public final ObjectId id;

    public final String target;

    public static final RefEntry EMPTY = new RefEntry(null, null, null);

    private RefEntry(final String name, final ObjectId id, final String target) {
        this.name = name;
        this.id = id;
        this.target = target;
    }

    public RefEntry(final String name, final ObjectId id) {
        this(name, id, null);
    }

    public RefEntry(final String name, final String target) {
        this(name, null, target);
    }

    public RefEntry(final Ref ref) {
        this(ref.getName(),
             ref.isSymbolic() ? null : ref.getObjectId(),
             ref.isSymbolic() ? ref.getTarget().getName() : null);
    }

    public boolean isSymbolic() {
        return target != null;
    }

    @Override
    public String toString() {
        return String.format("<Ref:%s %s>", name, target != null ? target : id.name());
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, name, target);
    }

    @Override
    public boolean equals(final Object obj) {
        if (!(obj instanceof RefEntry)) {
            return false;
        }
        final RefEntry that = (RefEntry) obj;
        return Objects.equals(this.id, that.id) &&
               Objects.equals(this.name, that.name) &&
               Objects.equals(this.target, that.target);
    }
}
