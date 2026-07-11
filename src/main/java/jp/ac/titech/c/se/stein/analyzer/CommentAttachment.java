package jp.ac.titech.c.se.stein.analyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jp.ac.titech.c.se.stein.core.SourceText.Fragment;

/**
 * The backend-neutral rule that decides which comments attach to a declaration, following JDT: a doc
 * comment directly above binds regardless of a blank line; other leading comments chain upward while no
 * blank line intervenes, stopping at one that trails the previous sibling; trailing comments chain
 * downward until a blank line, and — unless the declaration is the last of its siblings — only when a
 * blank line separates the run from the next declaration.
 */
final class CommentAttachment {
    private CommentAttachment() {
    }

    /**
     * A declaration or comment among its siblings, exposing just what the attachment rule needs, so the
     * rule is independent of the backend's node type.
     */
    interface Node {
        Node previous();

        Node next();

        int startRow();

        int endRow();

        boolean isComment();

        boolean isDocComment();

        boolean isNamed();

        Fragment fragment();
    }

    /**
     * The comment nodes attached to the given declaration, in source order.
     */
    static List<Node> attached(final Node node) {
        final List<Node> result = leading(node);
        result.addAll(trailing(node));
        return result;
    }

    private static List<Node> leading(final Node node) {
        final List<Node> run = new ArrayList<>();
        Node prev = node.previous();
        int startRow = node.startRow();
        if (prev != null && prev.isComment() && prev.isDocComment()) {
            run.add(prev);
            startRow = prev.startRow();
            prev = prev.previous();
        }
        final int nodeStartRow = startRow;
        int previousEndRow = 0;
        Node p = prev;
        while (p != null && p.isComment()) {
            p = p.previous();
        }
        if (p != null) {
            previousEndRow = p.endRow();
        }
        while (prev != null && prev.isComment()) {
            final int commentRow = prev.startRow();
            if (startRow - prev.endRow() > 1) {
                break; // a blank line between the comment and what follows it
            }
            if (commentRow == previousEndRow && commentRow != nodeStartRow) {
                break; // trails the previous sibling
            }
            run.add(prev);
            startRow = commentRow;
            prev = prev.previous();
        }
        Collections.reverse(run);
        return run;
    }

    private static List<Node> trailing(final Node node) {
        final List<Node> run = new ArrayList<>();
        final int nodeEndRow = node.endRow();
        int endRow = nodeEndRow;
        int sameLineCount = 0;
        Node next = node.next();
        while (next != null && next.isComment()) {
            if (next.startRow() - endRow > 1) {
                break; // a blank line between the previous end and the comment
            }
            run.add(next);
            endRow = next.endRow();
            if (next.startRow() == nodeEndRow) {
                sameLineCount = run.size();
            }
            next = next.next();
        }
        if (run.isEmpty()) {
            return run;
        }
        // unless this is the last sibling, the run trails this declaration only when a blank line
        // separates it from the next declaration; otherwise only the comments on its own end line trail
        if (next != null && next.isNamed() && next.startRow() - endRow <= 1) {
            return new ArrayList<>(run.subList(0, sameLineCount));
        }
        return run;
    }
}
