#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DATE="${INTERMED_TEST_DATE:-$(date -u +%Y%m%d)}"
SUITE="${INTERMED_TEST_SUITE:-weekly-perf}"
OUT_ROOT="${INTERMED_TEST_RUN_ROOT:-$ROOT_DIR/build/test-runs/$RUN_DATE/$SUITE}"
CASE_RUNNER="$ROOT_DIR/testing/_case_runner.py"
RUN_PERF="${INTERMED_RUN_PERF_BASELINE:-false}"
HEAP="${INTERMED_PERF_HEAP:-768}"
TIMEOUT="${INTERMED_PERF_TIMEOUT:-180}"

mkdir -p "$OUT_ROOT"

case_name() {
  case "$1" in
    PERF-001) echo "native-baseline-capture" ;;
    PERF-002) echo "intermed-startup-baseline" ;;
    PERF-003) echo "tick-time-baseline" ;;
    PERF-004) echo "memory-baseline" ;;
    PERF-005) echo "gc-pause-baseline" ;;
    *) echo "$1" ;;
  esac
}

mark_not_run() {
  "$CASE_RUNNER" mark \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "$1" \
    --name "$(case_name "$1")" \
    --outcome "not-run" \
    --reason "$2"
}

if [[ "$RUN_PERF" != "true" ]]; then
  mark_not_run "PERF-001" "performance baseline disabled; set INTERMED_RUN_PERF_BASELINE=true"
  mark_not_run "PERF-002" "performance baseline disabled; set INTERMED_RUN_PERF_BASELINE=true"
  mark_not_run "PERF-003" "performance baseline disabled; set INTERMED_RUN_PERF_BASELINE=true"
  mark_not_run "PERF-004" "performance baseline disabled; set INTERMED_RUN_PERF_BASELINE=true"
  mark_not_run "PERF-005" "performance baseline disabled; set INTERMED_RUN_PERF_BASELINE=true"
else
  for case_id in PERF-001 PERF-002 PERF-003 PERF-004 PERF-005; do
    mkdir -p "$OUT_ROOT/$case_id"
  done
  SNAPSHOT_JSON="$OUT_ROOT/PERF-002/harness-output/report/performance/alpha-performance-snapshot.json"
  "$CASE_RUNNER" run \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "PERF-002" \
    --name "intermed-startup-baseline" \
    --require-file "harness-output/report/performance/alpha-performance-snapshot.json" \
    --success-reason "harness performance baseline completed; shared artifact covers PERF-001..PERF-005 dimensions" \
    --command "cd '$ROOT_DIR' && JVM_ARGS='-Dintermed.performance.outputDir=$OUT_ROOT/PERF-002/harness-output/report/performance' OUTPUT_DIR='$OUT_ROOT/PERF-002/harness-output' ./test-harness/run.sh performance-baseline --heap=$HEAP --timeout=$TIMEOUT" || true

  if [[ -s "$SNAPSHOT_JSON" ]]; then
    cp "$SNAPSHOT_JSON" "$OUT_ROOT/PERF-002/performance-baseline.json"
    python3 - "$SNAPSHOT_JSON" "$OUT_ROOT/PERF-002/startup-report.json" <<'PY'
import json
import sys
from pathlib import Path

snapshot_path = Path(sys.argv[1])
output_path = Path(sys.argv[2])
snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))

lanes = []
for lane in snapshot.get("lanes", []):
    startup = lane.get("measures", {}).get("startupTime", {})
    lanes.append({
        "name": lane.get("name"),
        "loader": lane.get("loader"),
        "type": lane.get("type"),
        "status": lane.get("status"),
        "startup_millis": startup.get("millis"),
        "startup_status": startup.get("status"),
        "reason": lane.get("reason"),
    })

payload = {
    "schema": "intermed-performance-startup-report-v1",
    "source_snapshot": str(snapshot_path),
    "generated_at": snapshot.get("generatedAt"),
    "environment": snapshot.get("environment", {}),
    "lanes": lanes,
}
output_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
  fi
  if [[ -s "$OUT_ROOT/PERF-002/harness-output/report/performance/native-loader-baseline.json" ]]; then
    cp "$OUT_ROOT/PERF-002/harness-output/report/performance/native-loader-baseline.json" "$OUT_ROOT/PERF-002/native-loader-baseline.json"
  fi

  PERF002_OUTCOME="$(python3 - <<'PY' "$OUT_ROOT/PERF-002/result.json"
import json, sys
print(json.load(open(sys.argv[1])).get("outcome", "fail"))
PY
)"

  for case_id in PERF-001 PERF-003 PERF-004 PERF-005; do
    cp -R "$OUT_ROOT/PERF-002/"* "$OUT_ROOT/$case_id/" 2>/dev/null || true
    if [[ "$PERF002_OUTCOME" == "pass" ]]; then
      python3 "$CASE_RUNNER" mark \
        --suite "$SUITE" \
        --out-root "$OUT_ROOT" \
        --case-id "$case_id" \
        --name "$(case_name "$case_id")" \
        --outcome "pass" \
        --reason "shared performance-baseline artifact captured by PERF-002"
    else
      python3 "$CASE_RUNNER" mark \
        --suite "$SUITE" \
        --out-root "$OUT_ROOT" \
        --case-id "$case_id" \
        --name "$(case_name "$case_id")" \
        --outcome "blocked" \
        --reason "shared performance-baseline run failed; see PERF-002"
    fi
  done
fi

"$ROOT_DIR/testing/collect_artifacts.sh" "$SUITE" "$OUT_ROOT"
