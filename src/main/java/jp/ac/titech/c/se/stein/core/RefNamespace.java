package jp.ac.titech.c.se.stein.core;

import lombok.EqualsAndHashCode;
import org.eclipse.jgit.lib.ObjectIdRef;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.SymbolicRef;

/**
 * A view of a repository's refs under a fixed prefix, adapting between stored refs (as they live in
 * the repository, e.g. {@code refs/xyz/refs/heads/main}) and the logical {@link RefEntry} the rewriter
 * sees (e.g. {@code refs/heads/main} or {@code HEAD}). The root namespace (empty prefix) is the
 * identity view over the whole repository.
 */
@EqualsAndHashCode
public final class RefNamespace {
    public static final RefNamespace ROOT = new RefNamespace("");

    private final String prefix;

    public RefNamespace(final String prefix) {
        this.prefix = prefix;
    }

    /**
     * Whether this is the identity view over the whole repository.
     */
    public boolean isRoot() {
        return prefix.isEmpty();
    }

    /**
     * Whether the given stored ref name falls within this namespace.
     */
    public boolean owns(final String storedName) {
        return storedName.startsWith(prefix);
    }

    /**
     * The stored name for the given logical ref name.
     */
    public String toStored(final String logicalName) {
        return prefix + logicalName;
    }

    /**
     * The logical name for the given stored ref name within this namespace.
     */
    public String toLogical(final String storedName) {
        return storedName.substring(prefix.length());
    }

    /**
     * Reads a stored JGit ref into a logical {@link RefEntry}, translating its name (and, for a
     * symbolic ref, its target name) out of this namespace.
     */
    public RefEntry toLogicalEntry(final Ref stored) {
        return stored.isSymbolic()
                ? RefEntry.of(toLogical(stored.getName()), toLogical(stored.getTarget().getName()))
                : RefEntry.of(toLogical(stored.getName()), stored.getObjectId());
    }

    /**
     * Re-presents a logical {@link RefEntry} as a stored JGit ref, translating its name (and, for a
     * symbolic ref, its target name) into this namespace. For a symbolic ref a synthetic target ref
     * carries the stored target name.
     */
    public Ref toStoredRef(final RefEntry logical) {
        final String storedName = toStored(logical.name);
        return logical.isSymbolic()
                ? new SymbolicRef(storedName, new ObjectIdRef.Unpeeled(Ref.Storage.LOOSE, toStored(logical.target), null))
                : new ObjectIdRef.Unpeeled(Ref.Storage.LOOSE, storedName, logical.id);
    }
}
