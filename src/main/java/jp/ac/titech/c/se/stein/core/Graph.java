package jp.ac.titech.c.se.stein.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jgrapht.alg.lca.NaiveLCAFinder;
import org.jgrapht.graph.DirectedAcyclicGraph;

import jp.ac.titech.c.se.stein.core.Graph.Edge;
import jp.ac.titech.c.se.stein.core.Graph.Vertex;

/**
 * jGraphT DAG for a commit graph.
 */
public class Graph extends DirectedAcyclicGraph<Vertex, Edge> {
    private static final long serialVersionUID = 1L;

    /**
     * The constructor.
     */
    public Graph() {
        super(null, new EdgeSupplier(), false);
    }

    /**
     * Builds vertices and edges from a RevWalk.
     */
    public Graph build(final RevWalk walk) {
        try (final RevWalk w = walk) {
            for (final RevCommit commit : w) {
                final Vertex v = Vertex.of(commit);
                addVertex(v);
                for (final RevCommit parent : commit.getParents()) {
                    final Vertex p = Vertex.of(parent);
                    addVertex(p);
                    addEdge(v, p);
                }
            }
        }
        return this;
    }

    /**
     * Walk ObjectIds based on reversed topological order.
     */
    public void walk(final Consumer<ObjectId> f) {
        // TODO: More efficient implementation
        final List<ObjectId> ids = new ArrayList<>();
        for (final Vertex v : this) {
            ids.add(v.id);
        }
        for (int i = ids.size() - 1; i >= 0; i--) {
            f.accept(ids.get(i));
        }
    }

    /**
     * Checks whether two vertices can be merged (no parent-child relationship).
     */
    public boolean isMergeable(final Vertex base, final Vertex target) {
        final Set<Vertex> lca = new NaiveLCAFinder<Vertex, Edge>(this).getLCASet(base, target);
        return !lca.contains(base) && !lca.contains(target);
    }

    /**
     * Merge the <code>target</code> vertex into <code>base</code> vertex.
     *
     * @param base
     * @param target
     */
    public void mergeVertices(final Vertex base, final Vertex target) {
        if (base.equals(target)) {
            return;
        }
        // assert isMergeable(base, target);

        for (final Edge e : new ArrayList<>(outgoingEdgesOf(target))) {
            final Vertex v = getEdgeTarget(e);
            removeEdge(e);
            // This will use newly created edge having the latest index
            addEdge(base, v);
        }
        for (final Edge e : new ArrayList<>(incomingEdgesOf(target))) {
            final Vertex v = getEdgeSource(e);
            removeEdge(e);
            addEdge(v, base, e); // keep the original edge order
        }
        removeVertex(target);
    }

    /**
     * Gets the parent vertices of the given vertex.
     */
    public List<Vertex> getParents(final Vertex v) {
        final List<Edge> outgoings = new ArrayList<>(outgoingEdgesOf(v));
        Collections.sort(outgoings);

        final List<Vertex> result = new ArrayList<>();
        for (final Edge e : outgoings) {
            result.add(getEdgeTarget(e));
        }
        return result;
    }

    /**
     * Gets the parent IDs of the given ID.
     */
    public ObjectId[] getParentIds(final ObjectId id) {
        final List<Vertex> parents = getParents(Vertex.of(id));
        return parents.stream().map((v) -> v.id).toArray(ObjectId[]::new);
    }

    /**
     * Vertices of the graph (commits).
     */
    public static class Vertex {
        public final ObjectId id;

        public static Vertex of(final String name) {
            return of(ObjectId.fromString(name));
        }

        public static Vertex of(final ObjectId id) {
            return new Vertex(id);
        }

        public static Vertex of(final RevCommit commit) {
            return of(commit.getId());
        }

        protected Vertex(final ObjectId id) {
            this.id = id;
        }

        public boolean is(final String name) {
            return id.name().equals(name);
        }

        @Override
        public int hashCode() {
            return id.hashCode();
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof Vertex && id.equals(((Vertex) obj).id);
        }

        @Override
        public String toString() {
            return id.name();
        }
    }

    /**
     * Edges of the graph (parent relationship).
     */
    public static class Edge implements Comparable<Edge> {
        public final int index;

        Edge(final int index) {
            this.index = index;
        }

        @Override
        public int compareTo(Edge o) {
            return index - o.index;
        }
    }

    /**
     * An edge supplier that gives a sequential number to each edge.
     */
    public static class EdgeSupplier implements Supplier<Edge> {
        private int index = 0;

        @Override
        public Edge get() {
            return new Edge(++index);
        }
    }
}
