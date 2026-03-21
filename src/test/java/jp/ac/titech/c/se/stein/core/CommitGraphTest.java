package jp.ac.titech.c.se.stein.core;

import org.eclipse.jgit.lib.ObjectId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CommitGraphTest {
    private static CommitGraph.Vertex v(String hex40) {
        return CommitGraph.Vertex.of(ObjectId.fromString(hex40));
    }

    private static final CommitGraph.Vertex V1 = v("aa00000000000000000000000000000000000001");
    private static final CommitGraph.Vertex V2 = v("bb00000000000000000000000000000000000002");
    private static final CommitGraph.Vertex V3 = v("cc00000000000000000000000000000000000003");
    private static final CommitGraph.Vertex V4 = v("dd00000000000000000000000000000000000004");

    @Test
    public void testAddVertex() {
        final CommitGraph g = new CommitGraph();
        g.addVertex(V1);
        g.addVertex(V2);
        g.addEdge(V1, V2);
        assertTrue(g.containsVertex(V1));
        assertTrue(g.containsVertex(V2));
        assertTrue(g.containsEdge(V1, V2));
    }

    @Test
    public void testGetParents() {
        final CommitGraph g = new CommitGraph();
        g.addVertex(V1);
        g.addVertex(V2);
        g.addVertex(V3);
        g.addEdge(V1, V2);
        g.addEdge(V1, V3);
        final List<CommitGraph.Vertex> parents = g.getParents(V1);
        assertEquals(2, parents.size());
        assertTrue(parents.contains(V2));
        assertTrue(parents.contains(V3));
    }

    @Test
    public void testGetParentIds() {
        final CommitGraph g = new CommitGraph();
        g.addVertex(V1);
        g.addVertex(V2);
        g.addEdge(V1, V2);
        final ObjectId[] parentIds = g.getParentIds(V1.id);
        assertEquals(1, parentIds.length);
        assertEquals(V2.id, parentIds[0]);
    }

    @Test
    public void testMergeVertices() {
        // V1 -> V2, V3 -> V2
        // merge V1 into V3: V3 -> V2
        final CommitGraph g = new CommitGraph();
        g.addVertex(V1);
        g.addVertex(V2);
        g.addVertex(V3);
        g.addEdge(V1, V2);
        g.addEdge(V3, V2);
        assertTrue(g.mergeVertices(V3, V1));
        assertFalse(g.containsVertex(V1));
        assertTrue(g.containsVertex(V3));
        assertTrue(g.containsEdge(V3, V2));

        final CommitGraph g2 = new CommitGraph();
        g2.addVertex(V1);
        assertFalse(g2.mergeVertices(V1, V1));
    }

    @Test
    public void testIsAncestorDescendantRelationship() {
        // V1 -> V2 -> V3 (V1 is descendant of V3 via V2)
        final CommitGraph g = new CommitGraph();
        g.addVertex(V1);
        g.addVertex(V2);
        g.addVertex(V3);
        g.addEdge(V1, V2);
        g.addEdge(V2, V3);
        assertTrue(g.isAncestorDescendantRelationship(V1, V3));
        assertTrue(g.isAncestorDescendantRelationship(V3, V1)); // symmetric

        // V1 -> V3, V2 -> V3 (V1 and V2 are siblings)
        final CommitGraph g2 = new CommitGraph();
        g2.addVertex(V1);
        g2.addVertex(V2);
        g2.addVertex(V3);
        g2.addEdge(V1, V3);
        g2.addEdge(V2, V3);
        assertFalse(g2.isAncestorDescendantRelationship(V1, V2));
    }

    @Test
    public void testMergeVerticesSafelyRefusesAncestorDescendant() {
        // V1 -> V2 -> V3
        final CommitGraph g = new CommitGraph();
        g.addVertex(V1);
        g.addVertex(V2);
        g.addVertex(V3);
        g.addEdge(V1, V2);
        g.addEdge(V2, V3);
        assertFalse(g.mergeVerticesSafely(V1, V3));

        // V1 -> V3, V2 -> V3
        final CommitGraph g2 = new CommitGraph();
        g2.addVertex(V1);
        g2.addVertex(V2);
        g2.addVertex(V3);
        g2.addEdge(V1, V3);
        g2.addEdge(V2, V3);
        assertTrue(g2.mergeVerticesSafely(V1, V2));
    }

    @Test
    public void testIterator() {
        // V1 -> V2 -> V3 (V3 is root/oldest)
        final CommitGraph g = new CommitGraph();
        g.addVertex(V1);
        g.addVertex(V2);
        g.addVertex(V3);
        g.addEdge(V1, V2);
        g.addEdge(V2, V3);

        final Iterator<CommitGraph.Vertex> it = g.iterator();
        final List<CommitGraph.Vertex> order = new ArrayList<>();
        it.forEachRemaining(order::add);

        // Reversed topological: root (V3) should come before V1
        assertTrue(order.indexOf(V3) < order.indexOf(V2));
        assertTrue(order.indexOf(V2) < order.indexOf(V1));
    }

    @Test
    public void testVertexEquality() {
        final CommitGraph.Vertex a = CommitGraph.Vertex.of(V1.id);
        final CommitGraph.Vertex b = CommitGraph.Vertex.of(V1.id);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }
}
