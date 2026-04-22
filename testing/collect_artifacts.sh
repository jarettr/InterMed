#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DATE="${INTERMED_TEST_DATE:-$(date -u +%Y%m%d)}"
SUITE="${1:-${INTERMED_TEST_SUITE:-ci-core}}"
SUITE_DIR="${2:-${INTERMED_TEST_RUN_ROOT:-$ROOT_DIR/build/test-runs/$RUN_DATE/$SUITE}}"
SUMMARY_PATH="$SUITE_DIR/suite-summary.json"

mkdir -p "$SUITE_DIR"

python3 - "$SUITE_DIR" "$SUMMARY_PATH" "$SUITE" "$RUN_DATE" "$ROOT_DIR" <<'PY'
import json
import os
import platform
import subprocess
import sys
from pathlib import Path

suite_dir = Path(sys.argv[1])
summary_path = Path(sys.argv[2])
suite = sys.argv[3]
run_date = sys.argv[4]
root_dir = Path(sys.argv[5])
catalog_path = root_dir / "testing" / "test-cases.json"

outcomes = ["pass", "fail", "unsupported", "not-run", "blocked"]
counts = {outcome: 0 for outcome in outcomes}
results = []
artifact_index = {}
missing_required = {}
expected_artifacts = {}

required_default = [
    "run-manifest.json",
    "environment.json",
    "command.txt",
    "exit-status.json",
    "stdout.log",
    "stderr.log",
    "mod-list.json",
    "result.json",
]

def dedupe(items):
    seen = set()
    ordered = []
    for item in items:
        if item in seen:
            continue
        seen.add(item)
        ordered.append(item)
    return ordered

case_requirements = {}
if catalog_path.exists():
    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    profiles = catalog.get("artifact_profiles", {})
    for case in catalog.get("cases", []):
        required = []
        for profile in case.get("artifact_profiles", []):
            required.extend(profiles.get(profile, []))
        case_requirements[case["id"]] = dedupe(required or required_default)

for result_path in sorted(suite_dir.glob("*/result.json")):
    case_dir = result_path.parent
    try:
        result = json.loads(result_path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        result = {
            "case_id": case_dir.name,
            "outcome": "fail",
            "reason": f"invalid result.json: {exc}",
        }

    case_id = result.get("case_id", case_dir.name)
    outcome = result.get("outcome", "fail")
    if outcome not in counts:
        outcome = "fail"
        result["outcome"] = "fail"
        result["reason"] = f"invalid outcome for {case_id}"
    artifacts = sorted(
        str(path.relative_to(case_dir))
        for path in case_dir.rglob("*")
        if path.is_file()
    )
    artifact_index[case_id] = artifacts
    expected = required_default
    if outcome in {"pass", "fail", "unsupported"}:
        expected = case_requirements.get(case_id, required_default)
    expected_artifacts[case_id] = expected

    missing = [name for name in expected if not (case_dir / name).exists()]
    if missing:
        missing_required[case_id] = missing
        if outcome == "pass":
            outcome = "fail"
            result["outcome"] = "fail"
            existing_reason = result.get("reason", "")
            suffix = "missing required artifacts: " + ", ".join(missing)
            result["reason"] = f"{existing_reason}; {suffix}" if existing_reason else suffix

    counts[outcome] += 1

    results.append({
        "case_id": case_id,
        "name": result.get("name", case_id),
        "outcome": outcome,
        "reason": result.get("reason", ""),
        "case_dir": str(case_dir.relative_to(suite_dir)),
    })

first_failing = [
    item["case_id"]
    for item in results
    if item["outcome"] in {"fail", "blocked"}
][:10]

try:
    commit = subprocess.check_output(
        ["git", "-C", str(root_dir), "rev-parse", "HEAD"],
        stderr=subprocess.DEVNULL,
        text=True,
    ).strip()
except Exception:
    commit = "unknown"

summary = {
    "schema": "intermed-suite-summary-v1",
    "suite": suite,
    "date": run_date,
    "suite_dir": str(suite_dir),
    "commit": commit,
    "machine_class": os.environ.get("INTERMED_MACHINE_CLASS", "unknown"),
    "environment_id": os.environ.get("INTERMED_ENV_ID", "unknown"),
    "os": {
        "system": platform.system(),
        "release": platform.release(),
        "machine": platform.machine(),
    },
    "total_cases": len(results),
    "counts": counts,
    "first_failing_case_ids": first_failing,
    "missing_required_artifacts": missing_required,
    "expected_artifacts": expected_artifacts,
    "artifact_index": artifact_index,
    "results": results,
}

summary_path.write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n", encoding="utf-8")
print(summary_path)
PY
