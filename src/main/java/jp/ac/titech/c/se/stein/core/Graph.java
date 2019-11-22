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
import org.jgrapht.alg.lca.NaiveLCAFinder;
import org.jgrapht.graph.EdgeReversedGraph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.jgrapht.io.ComponentNameProvider;
import org.jgrapht.io.GmlExporter;
import org.jgrapht.io.GraphExporter;
import org.jgrapht.traverse.TopologicalOrderIterator;

import jp.ac.titech.c.se.stein.core.Graph.Edge;
import jp.ac.titech.c.se.stein.core.Graph.Vertex;

/**
 * jGraphT DAG for a commit graph.
 *
 * This inherits from SimpleDirectedGraph rather than DirectedAcyclicGraph due
 * to efficiency.
 */
public class Graph extends SimpleDirectedGraph<Vertex, Edge> implements Iterable<Vertex> {
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
    @Override
    public Iterator<Vertex> iterator() {
        return new TopologicalOrderIterator<>(new EdgeReversedGraph<>(this));
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
     * Merge the <code>target</code> vertex into <code>base</code> vertex only
     * if they are mergeable.
     *
     * @return true if they are mergeable and merged.
     */
    public boolean mergeVerticesSafely(final Vertex base, final Vertex target) {
        if (!isMergeable(base, target)) {
            return false;
        }
        mergeVertices(base, target);
        return true;
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
        final GraphExporter<Vertex, Edge> exporter = new GmlExporter<>(new VertexNameProvider(), null, new EdgeNameProvider(), null);
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

    /**
     * Name provider for GML export.
     */
    public static class VertexNameProvider implements ComponentNameProvider<Vertex> {
        @Override
        public String getName(final Vertex component) {
            return component.id.name();
        }
    }

    /**
     * Name provider for GML export.
     */
    public static class EdgeNameProvider implements ComponentNameProvider<Edge> {
        @Override
        public String getName(final Edge component) {
            return String.valueOf(component.index);
        }
    }
}
