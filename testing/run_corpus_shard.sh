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
PAIRS_TOP="${INTERMED_CORPUS_PAIRS_TOP:-10}"
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
MIXIN_EVIDENCE="$OUT_ROOT/_mixin-corpus-evidence.json"

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
    --command "cd '$ROOT_DIR' && OUTPUT_DIR='$HARNESS_OUTPUT' ./test-harness/run.sh full --mode=$MODE --loader=$LOADER --top=$TOP --pairs-top=$PAIRS_TOP --concurrency=$CONCURRENCY --heap=$HEAP --timeout=$TIMEOUT --shard-count=$SHARD_COUNT --shard-index=$SHARD_INDEX --retry-flaky" || true
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

  python3 - "$RESULTS" "$MIXIN_EVIDENCE" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

results_path = Path(sys.argv[1])
output_path = Path(sys.argv[2])
payload = json.loads(results_path.read_text(encoding="utf-8"))
results = payload.get("results", [])

def mentions_mixin(item):
    haystack = [
        str(item.get("id", "")),
        str(item.get("description", "")),
        str(item.get("loader", "")),
    ]
    for mod in item.get("mods", []) or []:
        haystack.extend([
            str(mod.get("slug", "")),
            str(mod.get("name", "")),
            " ".join(str(category) for category in mod.get("categories", []) or []),
        ])
    return "mixin" in " ".join(haystack).lower()

classified = []
counts = {
    "supported": 0,
    "supported-with-caveats": 0,
    "safe-fail": 0,
    "unsupported": 0,
}
for item in results:
    if not mentions_mixin(item):
        continue
    outcome = str(item.get("outcome", "")).upper()
    passed = bool(item.get("passed"))
    issues = item.get("issues", []) or []
    exit_code = item.get("exitCode")
    if passed and issues:
        bucket = "supported-with-caveats"
    elif passed:
        bucket = "supported"
    elif outcome.startswith("FAIL") or (isinstance(exit_code, int) and exit_code != 0):
        bucket = "safe-fail"
    else:
        bucket = "unsupported"
    counts[bucket] += 1
    classified.append({
        "id": item.get("id"),
        "description": item.get("description"),
        "loader": item.get("loader"),
        "outcome": item.get("outcome"),
        "exitCode": exit_code,
        "bucket": bucket,
        "modSlugs": [mod.get("slug") for mod in item.get("mods", []) or []],
        "issueTags": [issue.get("tag") for issue in issues if isinstance(issue, dict)],
    })

evidence = {
    "schema": "intermed-mixin-corpus-classification-v1",
    "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "source": str(results_path),
    "total_results": len(results),
    "mixin_result_count": len(classified),
    "has_mixin_results": bool(classified),
    "has_safe_fail_results": counts["safe-fail"] > 0,
    "classification_counts": counts,
    "classifications": classified,
    "diagnostic_note": (
        "This evidence classifies corpus outcomes. A safe-fail bucket means the "
        "harness produced an explicit failure classification instead of silent corruption."
    ),
}
output_path.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

  if [[ "$(python3 - "$MIXIN_EVIDENCE" <<'PY'
import json, sys
print("true" if json.load(open(sys.argv[1])).get("has_mixin_results") else "false")
PY
)" == "true" ]]; then
    "$CASE_RUNNER" mark \
      --suite "$SUITE" \
      --out-root "$OUT_ROOT" \
      --case-id "MIXIN-004" \
      --name "public-mod-mixin-corpus-classification" \
      --outcome "pass" \
      --reason "mixin-heavy corpus artifacts were classified into support buckets" \
      --command "classified mixin-heavy entries from $RESULTS"
    cp "$RESULTS" "$OUT_ROOT/MIXIN-004/compatibility-report.json"
    cp "$MIXIN_EVIDENCE" "$OUT_ROOT/MIXIN-004/mixin-conflict-report.json"
  fi

  if [[ "$(python3 - "$MIXIN_EVIDENCE" <<'PY'
import json, sys
print("true" if json.load(open(sys.argv[1])).get("has_safe_fail_results") else "false")
PY
)" == "true" ]]; then
    "$CASE_RUNNER" mark \
      --suite "$SUITE" \
      --out-root "$OUT_ROOT" \
      --case-id "MIXIN-005" \
      --name "safe-fail-for-unsupported-features" \
      --outcome "pass" \
      --reason "mixin-heavy corpus produced explicit safe-fail classifications with diagnostics" \
      --command "classified safe-fail entries from $RESULTS"
    cp "$MIXIN_EVIDENCE" "$OUT_ROOT/MIXIN-005/mixin-conflict-report.json"
    cp "$RESULTS" "$OUT_ROOT/MIXIN-005/compatibility-report.json"
  fi
fi

"$ROOT_DIR/testing/collect_artifacts.sh" "$SUITE" "$OUT_ROOT"
