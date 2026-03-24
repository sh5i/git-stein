#!/bin/sh
# Setup: clone target repo and generate sub-repositories by truncating at commit boundaries.
# Usage: ./setup-incremental.sh <repo-url> <work-dir> [splits] [delta-base-frac] [delta-step] [delta-count]
#
# Example:
#   ./setup-incremental.sh https://github.com/google/gson.git ./work 10 0.5 10 10
#
# This creates:
#   work/source.git          -- bare clone of the repo
#   work/splits/1 .. N       -- repos truncated at 1/N, 2/N, ..., (N-1)/N of commits
#   work/deltas/base         -- repo at delta-base-frac of total commits
#   work/deltas/1 .. M       -- repos at base + delta-step*1, base + delta-step*2, ...
set -eu

REPO_URL="${1:?Usage: setup-incremental.sh <repo-url> <work-dir> [splits] [delta-base-frac] [delta-step] [delta-count]}"
WORK_DIR="${2:?}"
SPLITS="${3:-10}"
DELTA_BASE_FRAC="${4:-0.5}"
DELTA_STEP="${5:-10}"
DELTA_COUNT="${6:-10}"

mkdir -p "$WORK_DIR"

# Clone source
SOURCE="$WORK_DIR/source.git"
if [ ! -d "$SOURCE" ]; then
    echo "Cloning $REPO_URL ..."
    git clone --bare "$REPO_URL" "$SOURCE"
fi

# Get first-parent commit list (oldest first)
COMMITS_FILE="$WORK_DIR/commits.txt"
git -C "$SOURCE" rev-list --first-parent HEAD | sed '1!G;h;$!d' > "$COMMITS_FILE"
TOTAL=$(wc -l < "$COMMITS_FILE" | tr -d ' ')
echo "Total first-parent commits: $TOTAL"

# Helper: create a repo truncated at commit N
create_truncated() {
    n="$1"
    dest="$2"
    sha=$(sed -n "${n}p" "$COMMITS_FILE")

    if [ -d "$dest" ]; then
        echo "  $dest already exists, skipping"
        return
    fi

    git clone --bare --no-tags "$SOURCE" "$dest" 2>/dev/null
    git -C "$dest" update-ref refs/heads/main "$sha"
    # Remove all other refs
    git -C "$dest" for-each-ref --format='%(refname)' | grep -v '^refs/heads/main$' | while read ref; do
        git -C "$dest" update-ref -d "$ref" 2>/dev/null || true
    done
    git -C "$dest" gc --prune=now --quiet 2>/dev/null || true
}

# Experiment A: splits
echo ""
echo "=== Creating $SPLITS splits ==="
mkdir -p "$WORK_DIR/splits"
STEP=$(( TOTAL / SPLITS ))
for i in $(seq 1 $(( SPLITS - 1 ))); do
    N=$(( STEP * i ))
    echo "Split $i/$SPLITS: $N commits"
    create_truncated "$N" "$WORK_DIR/splits/$i"
done
# Last split = full repo
if [ ! -d "$WORK_DIR/splits/$SPLITS" ]; then
    cp -r "$SOURCE" "$WORK_DIR/splits/$SPLITS"
fi

# Experiment B: deltas
echo ""
echo "=== Creating delta repos (base + step*N) ==="
mkdir -p "$WORK_DIR/deltas"
BASE_N=$(python3 -c "print(int($TOTAL * $DELTA_BASE_FRAC))")
echo "Base: $BASE_N commits"
create_truncated "$BASE_N" "$WORK_DIR/deltas/base"

for i in $(seq 1 "$DELTA_COUNT"); do
    N=$(( BASE_N + DELTA_STEP * i ))
    if [ "$N" -gt "$TOTAL" ]; then
        echo "Delta $i: $N exceeds total ($TOTAL), stopping"
        break
    fi
    echo "Delta $i: $N commits (+$(( DELTA_STEP * i )))"
    create_truncated "$N" "$WORK_DIR/deltas/$i"
done

echo ""
echo "Setup complete: $WORK_DIR"
