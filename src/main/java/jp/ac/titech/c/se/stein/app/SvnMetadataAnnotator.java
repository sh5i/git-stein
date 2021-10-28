package jp.ac.titech.c.se.stein.app;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jp.ac.titech.c.se.stein.Application;
import jp.ac.titech.c.se.stein.core.Context;
import jp.ac.titech.c.se.stein.core.RepositoryRewriter;
import jp.ac.titech.c.se.stein.core.Try;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "svn-metadata-annotator", description = "Attach metadata obtained from svn2git")
public class SvnMetadataAnnotator extends RepositoryRewriter {
    private static final Logger log = LoggerFactory.getLogger(SvnMetadataAnnotator.class);

    @Option(names = "--svn-mapping", paramLabel = "<log-git-repository>", description = "svn mapping",
            required = true)
    protected Path svnMappingFile;

    @Option(names = "--object-mapping", paramLabel = "<marks-git-repository>", description = "object mapping",
            required = true)
    protected Path objectMappingFile;

    protected Map<ObjectId, Integer> mapping;

    @Override
    protected void setUp(final Context c) {
        mapping = Try.io(() -> collectCommitMapping(svnMappingFile, objectMappingFile));
    }

    @Override
    protected String rewriteCommitMessage(final String message, final Context c) {
        final RevCommit commit = c.getCommit();
        final Integer svnId = mapping.get(commit.getId());
        if (svnId != null) {
            log.debug("Mapping: {} -> r{}", commit.getId().name(), svnId);
            return "svn:r" + svnId + " " + message;
        } else {
            return message;
        }
    }

    protected Map<ObjectId, Integer> collectCommitMapping(final Path svnMappingFile, final Path objectMappingFile) throws IOException {
        final Map<String, String> svnMapping = collectSvnMapping(svnMappingFile);
        final Map<String, String> objectMapping = collectObjectMapping(objectMappingFile);

        final Map<ObjectId, Integer> result = new HashMap<>();
        for (final Map.Entry<String, String> e : svnMapping.entrySet()) {
            final ObjectId gitId = ObjectId.fromString(objectMapping.get(e.getValue()));
            final Integer svnId = Integer.valueOf(e.getKey());
            result.put(gitId, svnId);
        }
        return result;
    }

    protected Map<String, String> collectSvnMapping(final Path file) throws IOException {
        final Pattern p = Pattern.compile("^progress SVN r(\\d+) branch master = :(\\d+)");
        try (final Stream<String> stream = Files.lines(file)) {
            return stream
                    .map(p::matcher)
                    .filter(Matcher::matches)
                    .collect(Collectors.toMap(m -> m.group(1), m -> m.group(2)));
        }
    }

    protected Map<String, String> collectObjectMapping(final Path file) throws IOException {
        final Pattern p = Pattern.compile("^:(\\d+) (\\w+)");
        try (final Stream<String> stream = Files.lines(file)) {
            return stream
                    .map(p::matcher)
                    .filter(Matcher::matches)
                    .collect(Collectors.toMap(m -> m.group(1), m -> m.group(2)));
        }
    }

    public static void main(final String[] args) {
        Application.execute(new SvnMetadataAnnotator(), args);
    }
}
