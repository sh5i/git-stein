package jp.ac.titech.c.se.stein.testing;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.app.Identity;
import jp.ac.titech.c.se.stein.app.blob.HistorageViaJDT;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.rewriter.RepositoryRewriter;
import jp.ac.titech.c.se.stein.util.TemporaryFile;
import org.eclipse.jgit.internal.storage.file.FileRepository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * Profiles memory usage of entryMapping during rewrite.
 * Usage: java -Xmx4g -cp ... MemoryProfile <repo-path> [command]
 *   command: identity (default) or historage
 */
public class MemoryProfile {
    public static void main(String[] args) throws Exception {
        final String repoPath = args.length > 0 ? args[0] : ".";
        final String command = args.length > 1 ? args[1] : "identity";
        final File sourceDir = new File(repoPath);

        if (!new File(sourceDir, ".git").exists() && !new File(sourceDir, "HEAD").exists()) {
            System.err.println("Not a git repository: " + sourceDir.getAbsolutePath());
            System.exit(1);
        }

        final Runtime rt = Runtime.getRuntime();
        System.out.printf("Max heap: %d MB%n", rt.maxMemory() / (1024 * 1024));
        System.out.printf("Repo: %s%n", sourceDir.getAbsolutePath());
        System.out.printf("Command: %s%n%n", command);

        final boolean isBare = !new File(sourceDir, ".git").exists();
        final FileRepository sourceRepo = openRepository(sourceDir, isBare);

        try (TemporaryFile.Directory tmp = TemporaryFile.directoryOf("mem-profile-")) {
            final FileRepository targetRepo = createRepository(tmp.getPath().toFile());

            final RepositoryRewriter rewriter = switch (command) {
                case "historage" -> new HistorageViaJDT().toRewriter();
                default -> new Identity();
            };
            rewriter.setConfig(new Application.Config());
            rewriter.initialize(sourceRepo, targetRepo);

            // Before
            System.gc();
            Thread.sleep(500);
            System.gc();
            final long heapBefore = usedHeap();
            System.out.printf("Before rewrite:%n");
            System.out.printf("  Heap used: %d MB%n%n", heapBefore / (1024 * 1024));

            // Run
            rewriter.rewrite(Context.init());

            // After (before GC)
            final long heapAfterNoGC = usedHeap();

            // After (after GC)
            System.gc();
            Thread.sleep(500);
            System.gc();
            final long heapAfterGC = usedHeap();

            // entryMapping size
            final int entryMappingSize = getEntryMappingSize(rewriter);

            System.out.printf("After rewrite:%n");
            System.out.printf("  Heap used (before GC): %d MB%n", heapAfterNoGC / (1024 * 1024));
            System.out.printf("  Heap used (after GC):  %d MB%n", heapAfterGC / (1024 * 1024));
            System.out.printf("  Heap delta (after GC): %d MB%n", (heapAfterGC - heapBefore) / (1024 * 1024));
            System.out.printf("  entryMapping size:     %d entries%n", entryMappingSize);
            if (entryMappingSize > 0) {
                final long deltaBytes = heapAfterGC - heapBefore;
                System.out.printf("  Approx bytes/entry:    %d bytes%n", deltaBytes / entryMappingSize);
            }

            sourceRepo.close();
            targetRepo.close();
        }
    }

    static int getEntryMappingSize(RepositoryRewriter rewriter) {
        try {
            Field f = RepositoryRewriter.class.getDeclaredField("entryMapping");
            f.setAccessible(true);
            Map<?, ?> map = (Map<?, ?>) f.get(rewriter);
            return map.size();
        } catch (Exception e) {
            System.err.println("Could not access entryMapping: " + e.getMessage());
            return -1;
        }
    }

    static long usedHeap() {
        final Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
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
}
