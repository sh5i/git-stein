#!/bin/sh
# Run incremental transformation benchmarks.
# Usage: ./bench-incremental.sh <jar-path> <work-dir> <command> [cache-opts...]
#
# Example:
#   ./bench-incremental.sh ./build/libs/git-stein-all.jar ./work @historage-jdt
#   ./bench-incremental.sh ./build/libs/git-stein-all.jar ./work @historage-jdt --cache commit,blob
#
# Runs two experiments:
#   A) Incremental over splits (1 -> 2 -> ... -> N)
#   B) Independent deltas from base (base+10, base+20, ...)
set -eu

JAR="${1:?Usage: bench-incremental.sh <jar-path> <work-dir> <command> [cache-opts...]}"
WORK_DIR="${2:?}"
COMMAND="${3:?}"
shift 3
CACHE_OPTS="$*"

RESULTS_DIR="$WORK_DIR/results"
mkdir -p "$RESULTS_DIR"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
LABEL=$(echo "$CACHE_OPTS" | tr ' ' '_')
[ -z "$LABEL" ] && LABEL="none"

TIME=/usr/bin/time

run_stein() {
    java -Xmx1g -jar "$JAR" --bare --log=WARN $CACHE_OPTS -o "$2" "$1" "$COMMAND"
}

# Capture wall-clock seconds from "time -p"
time_run_stein() {
    $TIME -p sh -c "run_stein='java -Xmx1g -jar $JAR --bare --log=WARN $CACHE_OPTS -o $2 $1 $COMMAND'; eval \"\$run_stein\"" 2>&1 | grep '^real ' | awk '{print $2}'
}

# ============================================================
# Experiment A: incremental over splits
# ============================================================
echo "=== Experiment A: Incremental splits (cache: ${CACHE_OPTS:-none}) ==="
RESULT_A="$RESULTS_DIR/${TIMESTAMP}_splits_${LABEL}.csv"
echo "step,commits,time_seconds" > "$RESULT_A"

SPLITS_DIR="$WORK_DIR/splits"
DEST_A="$WORK_DIR/dest_splits_${LABEL}"
rm -rf "$DEST_A"

SPLITS=$(ls -1d "$SPLITS_DIR"/[0-9]* 2>/dev/null | wc -l | tr -d ' ')

for i in $(seq 1 "$SPLITS"); do
    SOURCE="$SPLITS_DIR/$i"
    [ -d "$SOURCE" ] || continue
    NCOMMITS=$(git -C "$SOURCE" rev-list --all 2>/dev/null | wc -l | tr -d ' ')
    printf "  Split %d/%d (%d commits) ... " "$i" "$SPLITS" "$NCOMMITS"

    ELAPSED=$(time_run_stein "$SOURCE" "$DEST_A")

    echo "${ELAPSED}s"
    echo "$i,$NCOMMITS,$ELAPSED" >> "$RESULT_A"
done
echo "Results: $RESULT_A"
rm -rf "$DEST_A"

# ============================================================
# Experiment B: independent deltas from base
# ============================================================
echo ""
echo "=== Experiment B: Deltas from base (cache: ${CACHE_OPTS:-none}) ==="
RESULT_B="$RESULTS_DIR/${TIMESTAMP}_deltas_${LABEL}.csv"
echo "delta,commits,time_seconds" > "$RESULT_B"

DELTAS_DIR="$WORK_DIR/deltas"
BASE_SOURCE="$DELTAS_DIR/base"

# First, create the base destination
DEST_BASE="$WORK_DIR/dest_deltas_base_${LABEL}"
rm -rf "$DEST_BASE"
printf "  Building base ... "
BASE_TIME=$(time_run_stein "$BASE_SOURCE" "$DEST_BASE")
BASE_COMMITS=$(git -C "$BASE_SOURCE" rev-list --all 2>/dev/null | wc -l | tr -d ' ')
echo "$BASE_COMMITS commits, ${BASE_TIME}s"
echo "0,$BASE_COMMITS,$BASE_TIME" >> "$RESULT_B"

# Run deltas independently (cp base, then incremental transform)
DELTAS=$(ls -1d "$DELTAS_DIR"/[0-9]* 2>/dev/null | sort -n | while read d; do basename "$d"; done)

for i in $DELTAS; do
    DELTA_SOURCE="$DELTAS_DIR/$i"
    [ -d "$DELTA_SOURCE" ] || continue
    NCOMMITS=$(git -C "$DELTA_SOURCE" rev-list --all 2>/dev/null | wc -l | tr -d ' ')
    DIFF=$(( NCOMMITS - BASE_COMMITS ))
    printf "  Delta %s (+%d commits, total %d) ... " "$i" "$DIFF" "$NCOMMITS"

    DEST_DELTA="$WORK_DIR/dest_deltas_${LABEL}_${i}"
    cp -r "$DEST_BASE" "$DEST_DELTA"

    ELAPSED=$(time_run_stein "$DELTA_SOURCE" "$DEST_DELTA")

    echo "${ELAPSED}s"
    echo "$i,$NCOMMITS,$ELAPSED" >> "$RESULT_B"
    rm -rf "$DEST_DELTA"
done
echo "Results: $RESULT_B"
rm -rf "$DEST_BASE"

echo ""
echo "Done."
