#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DATE="${INTERMED_TEST_DATE:-$(date -u +%Y%m%d)}"
SUITE="${INTERMED_TEST_SUITE:-nightly-corpus}"
OUT_ROOT="${INTERMED_TEST_RUN_ROOT:-$ROOT_DIR/build/test-runs/$RUN_DATE/$SUITE}"
CASE_RUNNER="$ROOT_DIR/testing/_case_runner.py"
MODE="${INTERMED_CORPUS_MODE:-single}"
LOADER="${INTERMED_CORPUS_LOADER:-all}"
TOP="${INTERMED_CORPUS_TOP:-100}"
SHARD_COUNT="${INTERMED_CORPUS_SHARD_COUNT:-1}"
SHARD_INDEX="${INTERMED_CORPUS_SHARD_INDEX:-0}"
CONCURRENCY="${INTERMED_CORPUS_CONCURRENCY:-1}"
HEAP="${INTERMED_CORPUS_HEAP:-768}"
TIMEOUT="${INTERMED_CORPUS_TIMEOUT:-120}"
RUN_EXTERNAL="${INTERMED_RUN_EXTERNAL_CORPUS:-false}"

mkdir -p "$OUT_ROOT"

case_id_for_mode() {
  case "$MODE" in
    single) echo "CORPUS-001" ;;
    pairs) echo "CORPUS-002" ;;
    slices) echo "CORPUS-003" ;;
    *) echo "CORPUS-001" ;;
  esac
}

name_for_case() {
  case "$1" in
    CORPUS-001) echo "single-mod-corpus" ;;
    CORPUS-002) echo "pair-mod-corpus" ;;
    CORPUS-003) echo "curated-slice-packs" ;;
    *) echo "corpus-shard" ;;
  esac
}

CASE_ID="$(case_id_for_mode)"
CASE_NAME="$(name_for_case "$CASE_ID")"
HARNESS_OUTPUT="$OUT_ROOT/$CASE_ID/harness-output"
RESULTS="$HARNESS_OUTPUT/report/results-booted.json"

if [[ "$RUN_EXTERNAL" != "true" ]]; then
  "$CASE_RUNNER" mark \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "$CASE_ID" \
    --name "$CASE_NAME" \
    --outcome "not-run" \
    --reason "external corpus execution is disabled; set INTERMED_RUN_EXTERNAL_CORPUS=true to run this shard" \
    --command "INTERMED_RUN_EXTERNAL_CORPUS=true INTERMED_CORPUS_MODE=$MODE INTERMED_CORPUS_LOADER=$LOADER testing/run_corpus_shard.sh"
else
  "$CASE_RUNNER" run \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "$CASE_ID" \
    --name "$CASE_NAME" \
    --require-file "harness-output/report/results-booted.json" \
    --success-reason "harness corpus shard completed" \
    --command "cd '$ROOT_DIR' && OUTPUT_DIR='$HARNESS_OUTPUT' ./test-harness/run.sh full --mode=$MODE --loader=$LOADER --top=$TOP --concurrency=$CONCURRENCY --heap=$HEAP --timeout=$TIMEOUT --shard-count=$SHARD_COUNT --shard-index=$SHARD_INDEX --retry-flaky" || true
fi

if [[ -s "$RESULTS" ]]; then
  cp "$RESULTS" "$OUT_ROOT/$CASE_ID/compatibility-report.json"
  python3 - "$OUT_ROOT/$CASE_ID" "$RESULTS" "$CASE_ID" <<'PY'
import json
import sys
from pathlib import Path

case_dir = Path(sys.argv[1])
results_path = Path(sys.argv[2])
case_id = sys.argv[3]
payload = json.loads(results_path.read_text(encoding="utf-8"))
results = payload.get("results", [])

reason = None
if not results:
    reason = "harness completed but selected zero corpus cases"
elif case_id == "CORPUS-002" and not any(str(item.get("id", "")).startswith("pair-") for item in results):
    reason = "harness completed but did not execute any pair-* cases; increase corpus size or pairs-top"
elif case_id == "CORPUS-003" and not any(str(item.get("id", "")).startswith("slice-") for item in results):
    reason = "harness completed but did not execute any slice-* cases; increase curated corpus coverage"

if reason:
    result_path = case_dir / "result.json"
    result = json.loads(result_path.read_text(encoding="utf-8"))
    result["outcome"] = "not-run"
    result["reason"] = reason
    result_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    stdout = case_dir / "stdout.log"
    with stdout.open("a", encoding="utf-8") as handle:
        handle.write("\n[corpus-normalizer] " + reason + "\n")
PY
fi

"$ROOT_DIR/testing/collect_artifacts.sh" "$SUITE" "$OUT_ROOT"
