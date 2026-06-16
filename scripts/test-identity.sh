#!/usr/bin/env bash
set -euo pipefail

# Usage: scripts/test-identity.sh <github-repo-url>
# Example: scripts/test-identity.sh https://github.com/sh5i/git-stein

REPO_URL="${1:?Usage: $0 <github-repo-url>}"
WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

SOURCE_DIR="$WORK_DIR/source.git"
TARGET_DIR="$WORK_DIR/target.git"
SCRIPT_DIR="$(cd "$(dirname "$0")/.." && pwd)"

# --- collection ---

# Commit IDs reachable from non-notes refs, in topological order.
collect_commits() {  # repo
    git -C "$1" rev-list --topo-order --reverse \
        $(git -C "$1" show-ref | grep -v refs/notes/ | awk '{print $2}')
}

# (object, refname) pairs including peeled tag targets, excluding notes refs.
collect_refs() {  # repo
    git -C "$1" show-ref --dereference 2>/dev/null | grep -v ' refs/notes/' | sort -k2
}

# --- forensic helpers (only used on failure) ---

# Raw byte diff of two same-typed objects.
obj_byte_diff() {  # src_repo src_obj dst_repo dst_obj
    local t
    t=$(git -C "$1" cat-file -t "$2")
    diff --unified=3 \
        <(git -C "$1" cat-file "$t" "$2" | xxd) \
        <(git -C "$3" cat-file "$t" "$4" | xxd) | head -40 || true
}

# Tree entries "<mode> <type> <id> <name>", sorted by name.
tree_entries() {  # repo tree
    git -C "$1" cat-file -p "$2" | sort -k4
}

# First "<position> <src> <dst>" where the two id lists differ; empty if identical.
first_divergence() {  # before_file after_file
    paste "$1" "$2" | awk '$1 != $2 { print NR, $1, $2; exit }'
}

# Recursively locate and report the first differing entry between two trees.
drill_tree() {  # src_repo dst_repo src_tree dst_tree path
    local src_repo="$1" dst_repo="$2" src_tree="$3" dst_tree="$4" path="$5"
    local src_entries dst_entries
    src_entries=$(tree_entries "$src_repo" "$src_tree")
    dst_entries=$(tree_entries "$dst_repo" "$dst_tree")

    if [ "$(echo "$src_entries" | awk '{print $4}')" != "$(echo "$dst_entries" | awk '{print $4}')" ]; then
        echo "  [${path:-/}] entry names differ:"
        diff <(echo "$src_entries" | awk '{print $4}') <(echo "$dst_entries" | awk '{print $4}') | head -10 || true
        return
    fi

    local sm st sid name dm did
    while read -r sm st sid name <&3 && read -r dm _ did _ <&4; do
        if [ "$sid" = "$did" ]; then continue; fi
        echo "  [${path}${name}] $st differs: $sm $sid -> $dm $did"
        if [ "$st" = tree ]; then
            drill_tree "$src_repo" "$dst_repo" "$sid" "$did" "${path}${name}/"
        elif [ "$st" = blob ]; then
            echo "    --- blob content diff ---"
            diff --unified=3 \
                <(git -C "$src_repo" cat-file -p "$sid") \
                <(git -C "$dst_repo" cat-file -p "$did") | head -20 || true
        fi
        return
    done 3< <(echo "$src_entries") 4< <(echo "$dst_entries")

    echo "  [${path:-/}] entries match but tree ids differ; raw tree diff:"
    obj_byte_diff "$src_repo" "$src_tree" "$dst_repo" "$dst_tree"
}

# --- failure reporters ---

# Where the before/after commit-id lists first diverge, and why.
report_commit_divergence() {
    local line_num src dst
    read -r line_num src dst < <(first_divergence "$BEFORE" "$AFTER") || true
    if [ -z "${line_num:-}" ]; then
        echo "ERROR: could not locate the diverging commit."
        return
    fi
    echo "First diverging commit at position $line_num / $COMMIT_COUNT:"
    echo "  source: $src"
    echo "  target: $dst"

    local src_tree dst_tree
    src_tree=$(git -C "$SOURCE_DIR" cat-file commit "$src" | sed -n 's/^tree //p')
    dst_tree=$(git -C "$TARGET_DIR" cat-file commit "$dst" | sed -n 's/^tree //p')
    if [ "$src_tree" = "$dst_tree" ]; then
        echo "Trees identical — difference is in commit metadata:"
        obj_byte_diff "$SOURCE_DIR" "$src" "$TARGET_DIR" "$dst"
    else
        echo "Trees differ — drilling into the tree:"
        drill_tree "$SOURCE_DIR" "$TARGET_DIR" "$src_tree" "$dst_tree" ""
    fi
}

# The first ref/tag object that differs.
report_ref_divergence() {
    echo "--- show-ref --dereference diff (source vs target, excl. notes) ---"
    diff --unified=0 "$REFS_BEFORE" "$REFS_AFTER" | head -40 || true
    join -j1 \
        <(awk '{print $2, $1}' "$REFS_BEFORE" | sort) \
        <(awk '{print $2, $1}' "$REFS_AFTER" | sort) \
        | awk '$2 != $3 { print $1, $2, $3 }' \
        | while read -r ref s d; do
            [ "$(git -C "$SOURCE_DIR" cat-file -t "$s" 2>/dev/null || true)" = tag ] || continue
            echo "First diverging tag object at $ref: $s -> $d"
            obj_byte_diff "$SOURCE_DIR" "$s" "$TARGET_DIR" "$d"
            break
        done
}

# --- main ---

echo "=== Cloning $REPO_URL ==="
git clone --bare "$REPO_URL" "$SOURCE_DIR"

echo "=== Collecting commit IDs before transformation ==="
BEFORE="$WORK_DIR/before.txt"
collect_commits "$SOURCE_DIR" > "$BEFORE"
COMMIT_COUNT=$(wc -l < "$BEFORE" | tr -d ' ')
echo "  $COMMIT_COUNT commits"

echo "=== Running identity transformation ==="
cd "$SCRIPT_DIR"
./gradlew -q run --args="--bare --no-notes --extra-attributes -j ${NTHREADS:-1} $SOURCE_DIR -o $TARGET_DIR @id"

echo "=== Collecting commit IDs after transformation ==="
AFTER="$WORK_DIR/after.txt"
collect_commits "$TARGET_DIR" > "$AFTER"
AFTER_COUNT=$(wc -l < "$AFTER" | tr -d ' ')
echo "  $AFTER_COUNT commits"

echo "=== Comparing commit IDs ==="
if [ "$COMMIT_COUNT" -ne "$AFTER_COUNT" ]; then
    echo "FAIL: commit count differs: $COMMIT_COUNT (source) vs $AFTER_COUNT (target)"
    echo "--- source refs ---"; git -C "$SOURCE_DIR" show-ref | grep -v refs/notes/ | sort -k2
    echo "--- target refs ---"; git -C "$TARGET_DIR" show-ref | grep -v refs/notes/ | sort -k2
    exit 1
fi
if ! diff -q "$BEFORE" "$AFTER" > /dev/null 2>&1; then
    echo "FAIL: commit IDs differ."
    report_commit_divergence
    exit 1
fi
echo "  All $COMMIT_COUNT commit IDs are identical."

# Refs & tag objects: dereference comparison catches annotated-tag object SHAs and ref bindings,
# which the commit-ID comparison cannot (rev-list peels tags to commits).
echo "=== Comparing refs & tag objects (show-ref --dereference) ==="
REFS_BEFORE="$WORK_DIR/refs_before.txt"
REFS_AFTER="$WORK_DIR/refs_after.txt"
collect_refs "$SOURCE_DIR" > "$REFS_BEFORE"
collect_refs "$TARGET_DIR" > "$REFS_AFTER"
REF_COUNT=$(wc -l < "$REFS_BEFORE" | tr -d ' ')
if diff -q "$REFS_BEFORE" "$REFS_AFTER" > /dev/null 2>&1; then
    echo "PASS: $COMMIT_COUNT commit IDs and $REF_COUNT ref entries (incl. tag objects) are identical."
    exit 0
fi
echo "FAIL: refs or tag objects differ (commit IDs matched)."
report_ref_divergence
exit 1
