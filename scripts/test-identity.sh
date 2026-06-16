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

# Collect commit IDs reachable from non-notes refs, in topological order.
collect_commits() {
    local repo="$1"
    git -C "$repo" rev-list --topo-order --reverse \
        $(git -C "$repo" show-ref | grep -v refs/notes/ | awk '{print $2}')
}

# Collect (object, refname) pairs including peeled tag targets, excluding notes refs.
collect_refs() {
    git -C "$1" show-ref --dereference 2>/dev/null | grep -v ' refs/notes/' | sort -k2
}

# Recursively compare two trees to locate the first differing entry (failure forensics).
drill_tree() {
    local src_repo="$1" dst_repo="$2" src_tree="$3" dst_tree="$4" path="$5"

    local src_entries="$WORK_DIR/src_tree_$RANDOM.txt"
    local dst_entries="$WORK_DIR/dst_tree_$RANDOM.txt"
    git -C "$src_repo" cat-file -p "$src_tree" | sort -k4 > "$src_entries"
    git -C "$dst_repo" cat-file -p "$dst_tree" | sort -k4 > "$dst_entries"

    local src_names dst_names
    src_names=$(awk '{print $4}' "$src_entries")
    dst_names=$(awk '{print $4}' "$dst_entries")

    if [ "$src_names" != "$dst_names" ]; then
        echo "  [${path:-/}] Entry names differ:"
        diff <(echo "$src_names") <(echo "$dst_names") | head -10 || true
        return
    fi

    while IFS= read -r name; do
        local src_line dst_line
        src_line=$(grep -E "[[:space:]]${name}$" "$src_entries" | head -1)
        dst_line=$(grep -E "[[:space:]]${name}$" "$dst_entries" | head -1)

        local src_mode src_type src_id dst_mode dst_id
        src_mode=$(echo "$src_line" | awk '{print $1}')
        src_type=$(echo "$src_line" | awk '{print $2}')
        src_id=$(echo "$src_line" | awk '{print $3}')
        dst_mode=$(echo "$dst_line" | awk '{print $1}')
        dst_id=$(echo "$dst_line" | awk '{print $3}')

        if [ "$src_id" != "$dst_id" ]; then
            local entry_path="${path}${name}"
            echo "  [$entry_path] $src_type differs:"
            echo "    source: $src_mode $src_id"
            echo "    target: $dst_mode $dst_id"

            if [ "$src_type" = "tree" ]; then
                drill_tree "$src_repo" "$dst_repo" "$src_id" "$dst_id" "${entry_path}/"
            elif [ "$src_type" = "blob" ]; then
                echo "    --- blob content diff ---"
                diff --unified=3 \
                    <(git -C "$src_repo" cat-file -p "$src_id") \
                    <(git -C "$dst_repo" cat-file -p "$dst_id") \
                    | head -20 || true
            fi
            return
        fi
    done <<< "$src_names"

    echo "  [${path:-/}] All entries match individually but tree IDs differ."
    echo "    Raw tree object diff:"
    diff \
        <(git -C "$src_repo" cat-file tree "$src_tree" | xxd) \
        <(git -C "$dst_repo" cat-file tree "$dst_tree" | xxd) \
        | head -30 || true
}

# Report where the before/after commit-ID lists first diverge (failure forensics).
report_commit_divergence() {
    local first_src="" first_dst="" line src dst
    while IFS= read -r line; do
        src=$(echo "$line" | cut -d'|' -f1)
        dst=$(echo "$line" | cut -d'|' -f2)
        if [ "$src" != "$dst" ]; then
            first_src="$src"
            first_dst="$dst"
            break
        fi
    done < <(paste -d'|' "$BEFORE" "$AFTER")

    if [ -z "$first_src" ]; then
        echo "ERROR: Could not find diverging commit."
        return
    fi

    local line_num
    line_num=$(grep -n "^${first_src}$" "$BEFORE" | head -1 | cut -d: -f1)
    echo "First diverging commit at position $line_num / $COMMIT_COUNT:"
    echo "  source: $first_src"
    echo "  target: $first_dst"
    echo ""

    echo "=== Commit object diff ==="
    local src_raw="$WORK_DIR/src_commit.txt" dst_raw="$WORK_DIR/dst_commit.txt"
    git -C "$SOURCE_DIR" cat-file commit "$first_src" > "$src_raw"
    git -C "$TARGET_DIR" cat-file commit "$first_dst" > "$dst_raw"
    if ! diff -q "$src_raw" "$dst_raw" > /dev/null 2>&1; then
        echo "Commit content differs:"
        diff --unified=0 "$src_raw" "$dst_raw" || true
        echo ""
    fi

    local src_tree dst_tree
    src_tree=$(sed -n 's/^tree //p' "$src_raw")
    dst_tree=$(sed -n 's/^tree //p' "$dst_raw")
    echo "Tree IDs:"
    echo "  source: $src_tree"
    echo "  target: $dst_tree"

    if [ "$src_tree" = "$dst_tree" ]; then
        echo "  Trees are identical. Difference is in commit metadata only."
        echo ""
        echo "=== Raw commit bytes diff ==="
        diff --unified=3 \
            <(git -C "$SOURCE_DIR" cat-file commit "$first_src" | xxd) \
            <(git -C "$TARGET_DIR" cat-file commit "$first_dst" | xxd) | head -40 || true
        return
    fi

    echo "  Trees differ — drilling down..."
    echo ""
    drill_tree "$SOURCE_DIR" "$TARGET_DIR" "$src_tree" "$dst_tree" ""
}

# Report the first differing tag object (failure forensics for the ref comparison).
report_ref_divergence() {
    echo "--- show-ref --dereference diff (source vs target, excl. notes) ---"
    diff --unified=0 "$REFS_BEFORE" "$REFS_AFTER" | head -40 || true
    echo ""
    join -j1 \
        <(awk '{print $2, $1}' "$REFS_BEFORE" | sort) \
        <(awk '{print $2, $1}' "$REFS_AFTER" | sort) \
        | awk '$2 != $3 { print $1, $2, $3 }' \
        | while read -r ref s d; do
            [ "$(git -C "$SOURCE_DIR" cat-file -t "$s" 2>/dev/null || true)" = "tag" ] || continue
            echo "First diverging tag object at $ref:"
            echo "  source: $s"
            echo "  target: $d"
            echo "=== Tag object raw bytes diff ==="
            diff --unified=3 \
                <(git -C "$SOURCE_DIR" cat-file tag "$s" | xxd) \
                <(git -C "$TARGET_DIR" cat-file tag "$d" | xxd) | head -40 || true
            break
        done
}

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
    echo "FAIL: Commit count differs: $COMMIT_COUNT (source) vs $AFTER_COUNT (target)"
    echo ""
    echo "--- Refs in source (excl. notes) ---"
    git -C "$SOURCE_DIR" show-ref | grep -v refs/notes/ | sort -k2
    echo ""
    echo "--- Refs in target (excl. notes) ---"
    git -C "$TARGET_DIR" show-ref | grep -v refs/notes/ | sort -k2
    exit 1
fi
if ! diff -q "$BEFORE" "$AFTER" > /dev/null 2>&1; then
    echo "FAIL: Commit IDs differ."
    echo ""
    report_commit_divergence
    exit 1
fi
echo "  All $COMMIT_COUNT commit IDs are identical."

# Refs & tag objects: dereference comparison.
# Catches annotated-tag object SHAs and ref bindings, which the commit-ID set comparison above
# does not (rev-list peels tags to commits, so a corrupted tag object is invisible there).
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

echo "FAIL: Refs or tag objects differ (commit IDs matched)."
echo ""
report_ref_divergence
exit 1
