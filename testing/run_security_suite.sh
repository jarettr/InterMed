#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DATE="${INTERMED_TEST_DATE:-$(date -u +%Y%m%d)}"
SUITE="${INTERMED_TEST_SUITE:-ci-core}"
OUT_ROOT="${INTERMED_TEST_RUN_ROOT:-$ROOT_DIR/build/test-runs/$RUN_DATE/$SUITE}"
CASE_RUNNER="$ROOT_DIR/testing/_case_runner.py"
GRADLE_BIN="${GRADLE_BIN:-$ROOT_DIR/gradlew}"
RUN_SECURITY_SUITE="${INTERMED_RUN_SECURITY_SUITE:-false}"

mkdir -p "$OUT_ROOT"

write_security_report_from_result() {
  local case_id="$1"
  local source="$2"
  local result_path="$OUT_ROOT/$case_id/result.json"
  local report_path="$OUT_ROOT/$case_id/security-report.json"

  if [[ ! -f "$result_path" ]]; then
    return 0
  fi

  python3 - "$result_path" "$report_path" "$source" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

result_path = Path(sys.argv[1])
report_path = Path(sys.argv[2])
source = sys.argv[3]
result = json.loads(result_path.read_text(encoding="utf-8"))
payload = {
    "schema": "intermed-security-report-v1",
    "case_id": result.get("case_id"),
    "outcome": result.get("outcome"),
    "reason": result.get("reason", ""),
    "evidence_level": "synthetic-tested",
    "source": source,
    "strict_lane": True,
    "permissive_result_used_as_security_proof": False,
    "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
}
report_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

mark_from_sec001() {
  local case_id="$1"
  local name="$2"
  local outcome="$3"
  local reason="$4"
  "$CASE_RUNNER" mark \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "$case_id" \
    --name "$name" \
    --outcome "$outcome" \
    --reason "$reason" \
    --command "shared result from SEC-001 strictSecurity lane"

  if [[ "$outcome" == "pass" || "$outcome" == "fail" || "$outcome" == "unsupported" ]]; then
    write_security_report_from_result "$case_id" "shared SEC-001 :app:strictSecurity lane"
  fi
}

if [[ "$RUN_SECURITY_SUITE" != "true" ]]; then
  mark_from_sec001 "SEC-001" "strict-denied-file-read" "not-run" "safe default: set INTERMED_RUN_SECURITY_SUITE=true to run :app:strictSecurity"
  mark_from_sec001 "SEC-002" "strict-allowed-file-read" "not-run" "safe default: set INTERMED_RUN_SECURITY_SUITE=true to run :app:strictSecurity"
  mark_from_sec001 "SEC-003" "unattributed-sensitive-operation-denied" "not-run" "safe default: set INTERMED_RUN_SECURITY_SUITE=true to run :app:strictSecurity"
  mark_from_sec001 "SEC-004" "network-connect-denied-allowed" "not-run" "safe default: set INTERMED_RUN_SECURITY_SUITE=true to run :app:strictSecurity"
  mark_from_sec001 "SEC-005" "process-spawn-denied" "not-run" "safe default: set INTERMED_RUN_SECURITY_SUITE=true to run :app:strictSecurity"
  mark_from_sec001 "SEC-006" "reflection-access-denied-diagnosed" "not-run" "safe default: set INTERMED_RUN_SECURITY_SUITE=true to run :app:strictSecurity"
  mark_from_sec001 "SEC-007" "native-library-routing-conflict-diagnostics" "not-run" "native routing conflict diagnostics require dedicated native smoke fixtures"
  mark_from_sec001 "SEC-008" "async-attribution-propagation" "not-run" "safe default: set INTERMED_RUN_SECURITY_SUITE=true to run :app:strictSecurity"
  mark_from_sec001 "SEC-009" "tccl-propagation" "not-run" "TCCL propagation is semi-automated and requires async/native fixture coverage"
  mark_from_sec001 "SEC-010" "sandbox-denial-and-grant-behavior" "not-run" "sandbox grant/denial field path is not wired into the beta smoke runner yet"
  "$ROOT_DIR/testing/collect_artifacts.sh" "$SUITE" "$OUT_ROOT"
  exit 0
fi

"$CASE_RUNNER" run \
  --suite "$SUITE" \
  --out-root "$OUT_ROOT" \
  --case-id "SEC-001" \
  --name "strict-denied-file-read" \
  --success-reason "shared strictSecurity lane passed; SEC-001..SEC-006 and SEC-008 are covered by the strict synthetic security lane" \
  --command "cd '$ROOT_DIR' && '$GRADLE_BIN' :app:strictSecurity -Dintermed.allowRemoteForgeRepo=true --console=plain" || true

SEC001_OUTCOME="$(python3 - <<'PY' "$OUT_ROOT/SEC-001/result.json"
import json, sys
print(json.load(open(sys.argv[1])).get("outcome", "fail"))
PY
)"
write_security_report_from_result "SEC-001" ":app:strictSecurity"

if [[ "$SEC001_OUTCOME" == "pass" ]]; then
  mark_from_sec001 "SEC-002" "strict-allowed-file-read" "pass" "shared strictSecurity lane passed"
  mark_from_sec001 "SEC-003" "unattributed-sensitive-operation-denied" "pass" "shared strictSecurity lane passed"
  mark_from_sec001 "SEC-004" "network-connect-denied-allowed" "pass" "shared strictSecurity lane passed"
  mark_from_sec001 "SEC-005" "process-spawn-denied" "pass" "shared strictSecurity lane passed"
  mark_from_sec001 "SEC-006" "reflection-access-denied-diagnosed" "pass" "shared strictSecurity lane passed"
  mark_from_sec001 "SEC-008" "async-attribution-propagation" "pass" "shared strictSecurity lane passed"
else
  mark_from_sec001 "SEC-002" "strict-allowed-file-read" "blocked" "shared strictSecurity lane failed; see SEC-001"
  mark_from_sec001 "SEC-003" "unattributed-sensitive-operation-denied" "blocked" "shared strictSecurity lane failed; see SEC-001"
  mark_from_sec001 "SEC-004" "network-connect-denied-allowed" "blocked" "shared strictSecurity lane failed; see SEC-001"
  mark_from_sec001 "SEC-005" "process-spawn-denied" "blocked" "shared strictSecurity lane failed; see SEC-001"
  mark_from_sec001 "SEC-006" "reflection-access-denied-diagnosed" "blocked" "shared strictSecurity lane failed; see SEC-001"
  mark_from_sec001 "SEC-008" "async-attribution-propagation" "blocked" "shared strictSecurity lane failed; see SEC-001"
fi

mark_from_sec001 "SEC-007" "native-library-routing-conflict-diagnostics" "not-run" "native routing conflict diagnostics require dedicated native smoke fixtures"
mark_from_sec001 "SEC-009" "tccl-propagation" "not-run" "TCCL propagation is semi-automated and requires async/native fixture coverage"
mark_from_sec001 "SEC-010" "sandbox-denial-and-grant-behavior" "not-run" "sandbox grant/denial field path is not wired into the beta smoke runner yet"

"$ROOT_DIR/testing/collect_artifacts.sh" "$SUITE" "$OUT_ROOT"
