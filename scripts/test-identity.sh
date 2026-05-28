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

echo "=== Cloning $REPO_URL ==="
git clone --bare "$REPO_URL" "$SOURCE_DIR"

# Collect commit IDs from non-notes refs
collect_commits() {
    local repo="$1"
    git -C "$repo" rev-list --topo-order --reverse \
        $(git -C "$repo" show-ref | grep -v refs/notes/ | awk '{print $2}')
}

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

echo "=== Comparing ==="
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

if diff -q "$BEFORE" "$AFTER" > /dev/null 2>&1; then
    echo "PASS: All $COMMIT_COUNT commit IDs are identical."
    exit 0
fi

echo "FAIL: Commit IDs differ."
echo ""

# Find the first diverging commit (oldest in topo order)
FIRST_SRC=""
FIRST_DST=""
while IFS= read -r line; do
    SRC=$(echo "$line" | cut -d'|' -f1)
    DST=$(echo "$line" | cut -d'|' -f2)
    if [ "$SRC" != "$DST" ]; then
        FIRST_SRC="$SRC"
        FIRST_DST="$DST"
        break
    fi
done < <(paste -d'|' "$BEFORE" "$AFTER")

if [ -z "$FIRST_SRC" ]; then
    echo "ERROR: Could not find diverging commit."
    exit 1
fi

LINE_NUM=$(grep -n "^${FIRST_SRC}$" "$BEFORE" | head -1 | cut -d: -f1)
echo "First diverging commit at position $LINE_NUM / $COMMIT_COUNT:"
echo "  source: $FIRST_SRC"
echo "  target: $FIRST_DST"
echo ""

# Compare commit objects field by field
echo "=== Commit object diff ==="
SRC_RAW="$WORK_DIR/src_commit.txt"
DST_RAW="$WORK_DIR/dst_commit.txt"
git -C "$SOURCE_DIR" cat-file commit "$FIRST_SRC" > "$SRC_RAW"
git -C "$TARGET_DIR" cat-file commit "$FIRST_DST" > "$DST_RAW"

if ! diff -q "$SRC_RAW" "$DST_RAW" > /dev/null 2>&1; then
    echo "Commit content differs:"
    diff --unified=0 "$SRC_RAW" "$DST_RAW" || true
    echo ""
fi

# Extract fields
src_tree=$(sed -n 's/^tree //p' "$SRC_RAW")
dst_tree=$(sed -n 's/^tree //p' "$DST_RAW")

echo "Tree IDs:"
echo "  source: $src_tree"
echo "  target: $dst_tree"

if [ "$src_tree" = "$dst_tree" ]; then
    echo "  Trees are identical. Difference is in commit metadata only."
    echo ""
    echo "=== Raw commit bytes diff ==="
    git -C "$SOURCE_DIR" cat-file commit "$FIRST_SRC" | xxd > "$WORK_DIR/src_hex.txt"
    git -C "$TARGET_DIR" cat-file commit "$FIRST_DST" | xxd > "$WORK_DIR/dst_hex.txt"
    diff --unified=3 "$WORK_DIR/src_hex.txt" "$WORK_DIR/dst_hex.txt" | head -40 || true
    exit 1
fi

echo "  Trees differ — drilling down..."
echo ""

# Recursively compare trees to find the root cause
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

        local src_mode src_type src_id dst_mode dst_type dst_id
        src_mode=$(echo "$src_line" | awk '{print $1}')
        src_type=$(echo "$src_line" | awk '{print $2}')
        src_id=$(echo "$src_line" | awk '{print $3}')
        dst_mode=$(echo "$dst_line" | awk '{print $1}')
        dst_type=$(echo "$dst_line" | awk '{print $2}')
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

drill_tree "$SOURCE_DIR" "$TARGET_DIR" "$src_tree" "$dst_tree" ""

exit 1
