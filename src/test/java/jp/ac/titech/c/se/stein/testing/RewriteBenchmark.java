package jp.ac.titech.c.se.stein.testing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.app.Identity;
import jp.ac.titech.c.se.stein.app.blob.HistorageViaJDT;
import jp.ac.titech.c.se.stein.app.blob.TokenizeViaJDT;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.rewriter.BlobTranslator;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import jp.ac.titech.c.se.stein.util.TemporaryFile;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Simple benchmark for rewrite operations.
 * Run via {@code ./gradlew benchmark} or {@code ./gradlew benchmark -PbenchRepo=/path/to/repo}.
 */
public class RewriteBenchmark {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void main(String[] args) throws Exception {
        final String repoPath = args.length > 0 ? args[0] : ".";
        final File sourceDir = new File(repoPath);

        if (!new File(sourceDir, ".git").exists() && !new File(sourceDir, "HEAD").exists()) {
            System.err.println("Not a git repository: " + sourceDir.getAbsolutePath());
            System.exit(1);
        }

        final boolean alternates = Arrays.asList(args).contains("--alternates");

        System.out.println("Benchmarking: " + sourceDir.getAbsolutePath() + (alternates ? " (alternates)" : ""));
        System.out.println();

        final List<JsonObject> results = new ArrayList<>();

        results.add(benchmark("identity", sourceDir, new Identity(), alternates));
        results.add(benchmark("tokenize-jdt", sourceDir, new TokenizeViaJDT().toRewriter(), alternates));
        results.add(benchmark("historage-jdt", sourceDir, new HistorageViaJDT().toRewriter(), alternates));
        results.add(benchmark("historage+tokenize", sourceDir,
                new BlobTranslator.Composite(new HistorageViaJDT(), new TokenizeViaJDT()), alternates));

        // summary
        System.out.println();
        System.out.printf("%-25s %10s %10s %10s%n", "Name", "Time(ms)", "Heap(MB)", "Commits");
        System.out.println("-".repeat(60));
        for (JsonObject r : results) {
            System.out.printf("%-25s %10d %10d %10d%n",
                    r.get("name").getAsString(),
                    r.get("timeMs").getAsLong(),
                    r.get("heapMb").getAsLong(),
                    r.get("commits").getAsInt());
        }

        // JSON output
        final JsonObject report = new JsonObject();
        report.addProperty("repo", sourceDir.getAbsolutePath());
        report.addProperty("timestamp", Instant.now().toString());
        report.add("results", GSON.toJsonTree(results));
        System.out.println();
        System.out.println(GSON.toJson(report));
    }

    static JsonObject benchmark(String name, File sourceDir, RepositoryRewriter rewriter, boolean useAlternates) throws IOException {
        System.out.printf("Running %-25s ... ", name);
        System.out.flush();

        try (TemporaryFile.Directory tmp = TemporaryFile.directoryOf("git-stein-bench-")) {
            final boolean isBare = !new File(sourceDir, ".git").exists();
            final FileRepository sourceRepo = openRepository(sourceDir, isBare);
            FileRepository targetRepo = createRepository(tmp.getPath().toFile());

            if (useAlternates) {
                final File objectsInfo = new File(targetRepo.getObjectDatabase().getDirectory(), "info");
                objectsInfo.mkdirs();
                final File alternatesFile = new File(objectsInfo, "alternates");
                final String sourceObjects = sourceRepo.getObjectDatabase().getDirectory().getAbsolutePath();
                Files.writeString(alternatesFile.toPath(), sourceObjects + "\n");
                // reopen to pick up alternates
                targetRepo.close();
                targetRepo = openRepository(tmp.getPath().toFile(), true);
            }

            rewriter.setConfig(new Application.Config());
            rewriter.initialize(sourceRepo, targetRepo);

            System.gc();
            final long heapBefore = usedHeap();

            final Instant start = Instant.now();
            rewriter.rewrite(Context.init());
            final Instant end = Instant.now();

            final JsonObject result = new JsonObject();
            result.addProperty("name", name);
            result.addProperty("timeMs", Duration.between(start, end).toMillis());
            result.addProperty("heapMb", Math.max(0, (usedHeap() - heapBefore) / (1024 * 1024)));
            result.addProperty("commits", countCommits(targetRepo));

            sourceRepo.close();
            targetRepo.close();

            System.out.printf("%d ms, %d MB heap%n",
                    result.get("timeMs").getAsLong(), result.get("heapMb").getAsLong());
            return result;
        }
    }

    static FileRepository openRepository(File dir, boolean isBare) throws IOException {
        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        if (isBare) {
            builder.setGitDir(dir).setBare();
        } else {
            builder.setWorkTree(dir).setGitDir(new File(dir, ".git"));
        }
        return (FileRepository) builder.readEnvironment().build();
    }

    static FileRepository createRepository(File dir) throws IOException {
        final FileRepositoryBuilder builder = new FileRepositoryBuilder();
        builder.setGitDir(dir).setBare();
        final FileRepository repo = (FileRepository) builder.build();
        repo.create(true);
        return repo;
    }

    static int countCommits(FileRepository repo) {
        try (RevWalk walk = new RevWalk(repo)) {
            for (Ref ref : repo.getRefDatabase().getRefs()) {
                if (ref.getName().startsWith("refs/heads/")) {
                    try {
                        walk.markStart(walk.parseCommit(ref.getObjectId()));
                    } catch (Exception e) {
                        // skip
                    }
                }
            }
            int count = 0;
            for (RevCommit ignored : walk) {
                count++;
            }
            return count;
        } catch (Exception e) {
            return -1;
        }
    }

    static long usedHeap() {
        final Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
