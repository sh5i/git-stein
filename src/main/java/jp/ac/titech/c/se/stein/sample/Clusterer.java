package jp.ac.titech.c.se.stein.sample;

import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import jp.ac.titech.c.se.stein.CLI;
import jp.ac.titech.c.se.stein.core.Config;
import jp.ac.titech.c.se.stein.core.Configurable;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.Graph;
import jp.ac.titech.c.se.stein.core.Graph.Vertex;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import jp.ac.titech.c.se.stein.core.Try;

public class Clusterer extends RepositoryRewriter implements Configurable {
    private static final Logger log = LoggerFactory.getLogger(Clusterer.class);

    private List<List<String>> clustersInfo;

    protected final Map<ObjectId, ObjectId> alternateMapping = new HashMap<>();

    protected File graphOutput;

    private final Graph graph = new Graph();

    @Override
    public void addOptions(final Config conf) {
        super.addOptions(conf);
        conf.addOption(null, "clusters", true, "set clustering info");
        conf.addOption(null, "dump-graph", true, "dump graph as GML");
    }

    @Override
    public void configure(final Config conf) {
        super.configure(conf);
        if (conf.hasOption("clusters")) {
            clustersInfo = loadClustersInfo(new File(conf.getOptionValue("clusters")));
        }
        if (conf.hasOption("dump-graph")) {
            graphOutput = new File(conf.getOptionValue("dump-graph"));
        }
    }

    /**
     * Loads the clustering info.
     */
    protected List<List<String>> loadClustersInfo(final File file) {
        final Gson gson = new Gson();
        final TypeToken<List<List<String>>> t = new TypeToken<List<List<String>>>() {};
        return Try.run(() -> gson.fromJson(new FileReader(file), t.getType()));
    }

    @Override
    protected void rewriteCommits(final Context c) {
        graph.build(prepareRevisionWalk(c));
        log.debug("Graph: {} vertices, {} edges ({})", graph.vertexSet().size(), graph.edgeSet().size(), c);

        mergeClusters();
        if (graphOutput != null) {
            graph.dump(graphOutput);
        }

        try (final ObjectInserter ins = writeRepo.newObjectInserter()) {
            this.inserter = ins;
            for (final Vertex v : graph) {
                rewriteCommit(Try.io(() -> repo.parseCommit(v.id)), c);
            }
            this.inserter = null;
        }

        for (final Map.Entry<ObjectId, ObjectId> e : alternateMapping.entrySet()) {
            final ObjectId merged = e.getKey();
            final ObjectId base = e.getValue();
            final ObjectId rewritten = commitMapping.get(base);
            if (rewritten == null) {
                log.warn("Base commit has not rewritten yet: base: {}, merged: {} ({})", base.name(), merged.name(), c);
            } else {
                log.debug("Add commit mapping: {} (merged into {}) -> {} ({})", merged.name(), base.name(), rewritten.name(), c);
                commitMapping.put(merged, rewritten);
            }
        }

        for (final Map.Entry<ObjectId, ObjectId> e : alternateMapping.entrySet()) {
            final ObjectId merged = e.getKey();
            final ObjectId base = e.getValue();
            final ObjectId rewritten = commitMapping.get(base);
            if (rewritten == null) {
                log.warn("Base commit has not rewritten yet: base: {}, merged: {} ({})", base.name(), merged.name(), c);
            } else {
                log.debug("Add commit mapping: {} (merged into {}) -> {} ({})", merged.name(), base.name(), rewritten.name(), c);
                commitMapping.put(merged, rewritten);
            }
        }
    }

    protected void mergeClusters() {
        for (final List<String> info : clustersInfo) {
            final List<Vertex> in = info.stream().map(Vertex::of).collect(Collectors.toList());
            final List<Vertex> out = mergeCluster(in);
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
    protected List<Vertex> mergeCluster(final List<Vertex> cluster) {
        final List<Vertex> result = new ArrayList<>();
        final Vertex base = cluster.get(0);
        result.add(base);
        cluster.stream().skip(1).forEach(v -> {
            if (graph.mergeVerticesSafely(base, v)) {
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
            log.debug("Substitute parents: {} -> {} ({})",
                    Stream.of(parents).map(ObjectId::name),
                    Stream.of(newParents).map(ObjectId::name),
                    c);
        }
        return super.rewriteParents(newParents, c);
    }

    public static void main(final String[] args) {
        new CLI(Clusterer.class, args).run();
    }
}
