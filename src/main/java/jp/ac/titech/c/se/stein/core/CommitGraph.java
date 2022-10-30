package jp.ac.titech.c.se.stein.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.jgrapht.Graph;
import org.jgrapht.alg.lca.NaiveLCAFinder;
import org.jgrapht.graph.EdgeReversedGraph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.jgrapht.nio.gml.GmlExporter;
import org.jgrapht.traverse.TopologicalOrderIterator;

import jp.ac.titech.c.se.stein.core.CommitGraph.Edge;
import jp.ac.titech.c.se.stein.core.CommitGraph.Vertex;

/**
 * jGraphT DAG for a commit graph.
 * <p>
 * This inherits from SimpleDirectedGraph rather than DirectedAcyclicGraph due
 * to efficiency.
 */
public class CommitGraph extends SimpleDirectedGraph<Vertex, Edge> implements Iterable<Vertex> {
    private static final long serialVersionUID = 1L;

    private final Graph<Vertex, Edge> reversed = new EdgeReversedGraph<>(this);

    /**
     * The constructor.
     */
    public CommitGraph() {
        super(null, new EdgeSupplier(), false);
    }

    /**
     * Builds vertices and edges from a RevWalk.
     */
    public CommitGraph build(final RevWalk walk) {
        try (final RevWalk w = walk) {
            // This process does not require body info
            w.setRetainBody(false);

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
    @Override
    public Iterator<Vertex> iterator() {
        return new TopologicalOrderIterator<>(reversed);
    }

    /**
     * Checks whether two vertices are in a ancestor-descendant relationship (either can be the ancestor side).
     */
    public boolean isAncestorDescendantRelationship(final Vertex base, final Vertex target) {
        final Set<Vertex> lca = new NaiveLCAFinder<>(reversed).getLCASet(base, target);
        return lca.contains(base) || lca.contains(target);
    }

    /**
     * Merge the <code>target</code> vertex into <code>base</code> vertex.
     */
    public boolean mergeVertices(final Vertex base, final Vertex target) {
        if (base.equals(target)) {
            return false;
        }
        // assert isMergeable(base, target);

        for (final Edge e : new ArrayList<>(outgoingEdgesOf(target))) {
            final Vertex v = getEdgeTarget(e);
            removeEdge(e);
            // This will use newly created edge having the latest index
            if (!base.equals(v)) {
                addEdge(base, v);
            }
        }
        for (final Edge e : new ArrayList<>(incomingEdgesOf(target))) {
            final Vertex v = getEdgeSource(e);
            removeEdge(e);
            if (!base.equals(v)) {
                addEdge(v, base, e); // keep the original edge order
            }
        }
        removeVertex(target);
        return true;
    }

    /**
     * Merge the <code>target</code> vertex into <code>base</code> vertex only
     * if they are mergeable.
     *
     * @return true if they are mergeable and merged.
     */
    public boolean mergeVerticesSafely(final Vertex base, final Vertex target) {
        if (isAncestorDescendantRelationship(base, target)) {
            return false;
        } else {
            return mergeVertices(base, target);
        }
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
        return parents.stream().map(v -> v.id).toArray(ObjectId[]::new);
    }

    /**
     * Dumps the graph as GML.
     */
    public void dump(final File file) {
        final GmlExporter<Vertex, Edge> exporter = new GmlExporter<>(v -> v.id.name());
        exporter.setEdgeIdProvider(e -> String.valueOf(e.index));
        Try.run(() -> exporter.exportGraph(this, file));
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
        public int compareTo(final Edge that) {
            return this.index - that.index;
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
