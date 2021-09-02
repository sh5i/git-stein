package jp.ac.titech.c.se.stein.app;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.CommitGraph;
import jp.ac.titech.c.se.stein.core.CommitGraph.Vertex;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.Context.Key;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import jp.ac.titech.c.se.stein.core.Try;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "clusterer", description = "Cluster and merge commits")
public class Clusterer extends RepositoryRewriter {
    private static final Logger log = LoggerFactory.getLogger(Clusterer.class);

    @Option(names = "--recipe", paramLabel = "<file>", description = "recipe JSON",
            required = true)
    protected File recipeFile;

    @Option(names = "--dump-graph", paramLabel = "<file>", description = "dump graph as GML")
    protected File graphFile;

    private Map<String, List<List<String>>> recipe;

    protected final Map<ObjectId, ObjectId> alternateMapping = new HashMap<>();

    private final CommitGraph graph = new CommitGraph();

    @Override
    public void setUp(final Context c) {
        recipe = loadRecipe(recipeFile);
    }

    /**
     * Loads the clustering info.
     */
    protected Map<String, List<List<String>>> loadRecipe(final File file) {
        final Gson gson = new Gson();
        final TypeToken<Map<String, List<List<String>>>> t = new TypeToken<Map<String, List<List<String>>>>() {};
        return Try.run(() -> gson.fromJson(new FileReader(file), t.getType()));
    }

    @Override
    protected void rewriteCommits(final Context c) {
        graph.build(prepareRevisionWalk(c));
        log.debug("Graph: {} vertices, {} edges {}", graph.vertexSet().size(), graph.edgeSet().size(), c);

        rewriteGraph();

        if (graphFile != null) {
            graph.dump(graphFile);
        }
        target.openInserter(ins -> {
            final Context uc = c.with(Key.inserter, ins);

            final RevWalk walk = source.walk(c);
            for (final Vertex v : graph) {
                rewriteCommit(Try.io(() -> walk.parseCommit(v.id)), uc);
            }
        }, c);

        for (final Map.Entry<ObjectId, ObjectId> e : alternateMapping.entrySet()) {
            final ObjectId merged = e.getKey();
            final ObjectId base = e.getValue();
            final ObjectId rewritten = commitMapping.get(base);
            if (rewritten == null) {
                log.warn("Base commit has not rewritten yet: base: {}, merged: {} {}", base.name(), merged.name(), c);
            } else {
                log.debug("Add commit mapping: {} (merged into {}) -> {} {}", merged.name(), base.name(), rewritten.name(), c);
                commitMapping.put(merged, rewritten);
            }
        }

        for (final Map.Entry<ObjectId, ObjectId> e : alternateMapping.entrySet()) {
            final ObjectId merged = e.getKey();
            final ObjectId base = e.getValue();
            final ObjectId rewritten = commitMapping.get(base);
            if (rewritten == null) {
                log.warn("Base commit has not rewritten yet: base: {}, merged: {} {}", base.name(), merged.name(), c);
            } else {
                log.debug("Add commit mapping: {} (merged into {}) -> {} {}", merged.name(), base.name(), rewritten.name(), c);
                commitMapping.put(merged, rewritten);
            }
        }
    }

    protected void rewriteGraph() {
        if (recipe.containsKey("removeEdges")) {
            removeEdges(recipe.get("removeEdges"));
        }
        if (recipe.containsKey("addEdges")) {
            addEdges(recipe.get("addEdges"));
        }
        if (recipe.containsKey("clusters")) {
            mergeClusters(recipe.get("clusters"), true);
        }
        if (recipe.containsKey("forcedClusters")) {
            mergeClusters(recipe.get("forcedClusters"), false);
        }
    }

    private void removeEdges(final List<List<String>> removeEdgesRecipe) {
        for (final List<String> e : removeEdgesRecipe) {
            final Vertex source = Vertex.of(e.get(0));
            final Vertex target = Vertex.of(e.get(1));
            graph.removeEdge(source, target);
            log.debug("Remove edge: {} -> {}", source, target);
        }
    }

    private void addEdges(final List<List<String>> addEdgesRecipe) {
        for (final List<String> e : addEdgesRecipe) {
            final Vertex source = Vertex.of(e.get(0));
            final Vertex target = Vertex.of(e.get(1));
            graph.addEdge(source, target);
            log.debug("Add edge: {} -> {}", source, target);
        }
    }

    private void mergeClusters(final List<List<String>> clustersRecipe, final boolean safe) {
        for (final List<String> c : clustersRecipe) {
            final List<Vertex> in = c.stream().map(Vertex::of).collect(Collectors.toList());
            final List<Vertex> out = mergeCluster(in, safe);
            log.debug("Merge cluster: {} -> {} (size: {} -> {})", in, out, in.size(), out.size());
        }
    }

    /**
     * Merges a commit cluster.
     *
     * @param cluster
     *            A string list of commit IDs.
     * @return A list of merged commits. If its size = 1, all the commits are
     *         merged into one.
     */
    protected List<Vertex> mergeCluster(final List<Vertex> cluster, final boolean safe) {
        final List<Vertex> result = new ArrayList<>();
        final Vertex base = cluster.get(0);
        result.add(base);
        cluster.stream().skip(1).forEach(v -> {
            if (safe ? graph.mergeVerticesSafely(base, v) : graph.mergeVertices(base, v)) {
                alternateMapping.put(v.id, base.id);
            } else {
                result.add(v);
            }
        });
        return result;
    }

    @Override
    protected ObjectId[] rewriteParents(final ObjectId[] parents, final Context c) {
        final ObjectId[] newParents = graph.getParentIds(c.getCommit().getId());
        if (log.isDebugEnabled()) {
            log.debug("Substitute parents: {} -> {} {}",
                    Stream.of(parents).map(ObjectId::name).toArray(String[]::new),
                    Stream.of(newParents).map(ObjectId::name).toArray(String[]::new),
                    c);
        }
        return super.rewriteParents(newParents, c);
    }

    public static void main(final String[] args) {
        Application.execute(new Clusterer(), args);
    }
}
