package jp.ac.titech.c.se.stein.jgit;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * An extended RevWalk that memorizes the markStart and uninteresting commits.
 */
public class RevWalk extends org.eclipse.jgit.revwalk.RevWalk {

    private final List<RevCommit> starts = new ArrayList<>();

    private final List<RevCommit> uninterestings = new ArrayList<>();

    public RevWalk(Repository repo) {
        super(repo);
    }

    public void memoMarkStart(RevCommit c) throws IOException {
        starts.add(c);
        markStart(c);
    }

    public void memoMarkStart(ObjectId id) throws IOException {
        memoMarkStart(parseCommit(id));
    }

    public void memoMarkUninteresting(RevCommit c) throws IOException {
        uninterestings.add(c);
        markUninteresting(c);
    }

    public void memoMarkUninteresting(ObjectId id) throws IOException {
        memoMarkUninteresting(parseCommit(id));
    }

    public void memoReset() throws IOException {
        reset();
        for (RevCommit c : starts) {
            markStart(c);
        }
        for (RevCommit c : uninterestings) {
            markUninteresting(c);
        }
    }
}
