#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DATE="${INTERMED_TEST_DATE:-$(date -u +%Y%m%d)}"
SUITE="${INTERMED_TEST_SUITE:-weekly-soak}"
OUT_ROOT="${INTERMED_TEST_RUN_ROOT:-$ROOT_DIR/build/test-runs/$RUN_DATE/$SUITE}"
CASE_RUNNER="$ROOT_DIR/testing/_case_runner.py"
GRADLE_BIN="${GRADLE_BIN:-$ROOT_DIR/gradlew}"
RUN_SOAK="${INTERMED_RUN_SOAK:-false}"
RUN_MEDIUM="${INTERMED_RUN_MEDIUM_SOAK:-false}"
OBS_FILE="${INTERMED_SOAK_OBSERVATIONS:-}"
MEDIUM_SESSION_ROOT="${INTERMED_SOAK_SESSION_ROOT:-$OUT_ROOT/manual-medium-soak}"
MEDIUM_OBS_TEMPLATE="$MEDIUM_SESSION_ROOT/observation-template.json"
MEDIUM_CHECKLIST="$MEDIUM_SESSION_ROOT/manual-checklist.md"

mkdir -p "$OUT_ROOT"

write_medium_soak_template() {
  mkdir -p "$MEDIUM_SESSION_ROOT"
  cat > "$MEDIUM_OBS_TEMPLATE" <<EOF
{
  "session": {
    "observer": "",
    "started_at": "",
    "ended_at": "",
    "duration_minutes": 0,
    "environment_id": "${INTERMED_ENV_ID:-unknown}",
    "machine_class": "${INTERMED_MACHINE_CLASS:-unknown}",
    "pack": "mixed-pack-medium-soak",
    "notes": ""
  },
  "cases": {
    "SOAK-002": {
      "outcome": "not-run",
      "reason": "",
      "stable_session": false,
      "duration_minutes": 0,
      "reconnect_possible": false,
      "no_severe_untriaged_regressions": false,
      "notes": ""
    }
  }
}
EOF

  cat > "$MEDIUM_CHECKLIST" <<EOF
# InterMed SOAK-002 Manual Checklist

Prepared observation template:
  $MEDIUM_OBS_TEMPLATE

Minimum valid evidence:
1. Run a mixed-pack client/server session for at least 120 minutes.
2. Keep server and client logs from the same session.
3. Reconnect once near the end of the run.
4. Confirm no crash, no runaway memory growth, and no untriaged severe registry/network corruption.
5. Fill \`$MEDIUM_OBS_TEMPLATE\`.
6. Re-run:
   \`INTERMED_RUN_SOAK=true INTERMED_RUN_MEDIUM_SOAK=true INTERMED_SOAK_OBSERVATIONS=$MEDIUM_OBS_TEMPLATE testing/run_soak.sh\`

This wrapper will reject a claimed pass if duration is below 120 minutes or the stability/reconnect/no-severe-regression checks are false.
EOF
}

write_medium_soak_report() {
  local target="$1"
  local record_json="$2"
  cp "$record_json" "$target/soak-report.json"
  cp "$OBS_FILE" "$target/observation.json"
  cp "$MEDIUM_OBS_TEMPLATE" "$target/observation-template.json" 2>/dev/null || true
  cp "$MEDIUM_CHECKLIST" "$target/manual-checklist.md" 2>/dev/null || true
}

mark_medium_from_observation() {
  local record_json
  local outcome
  local reason
  local stdout_payload
  record_json="$(mktemp)"

  python3 - "$OBS_FILE" "$record_json" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

valid = {"pass", "fail", "unsupported", "not-run", "blocked"}
source = Path(sys.argv[1])
target = Path(sys.argv[2])
payload = json.loads(source.read_text(encoding="utf-8"))
session = payload.get("session", {})
case = payload.get("cases", {}).get("SOAK-002", {})
outcome = case.get("outcome", "not-run")
if outcome not in valid:
    outcome = "fail"
    reason = "invalid SOAK-002 observation outcome"
else:
    reason = case.get("reason") or case.get("notes") or "manual medium soak observation recorded"

duration = max(
    float(session.get("duration_minutes") or 0),
    float(case.get("duration_minutes") or 0),
)
checks = {
    "duration_at_least_120_minutes": duration >= 120,
    "stable_session": bool(case.get("stable_session")),
    "reconnect_possible": bool(case.get("reconnect_possible")),
    "no_severe_untriaged_regressions": bool(case.get("no_severe_untriaged_regressions")),
}
if outcome == "pass" and not all(checks.values()):
    outcome = "fail"
    missing = ", ".join(name for name, ok in checks.items() if not ok)
    reason = f"manual medium soak pass validation failed: {missing}"

record = {
    "schema": "intermed-medium-soak-observation-v1",
    "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "case_id": "SOAK-002",
    "outcome": outcome,
    "reason": reason,
    "duration_minutes": duration,
    "checks": checks,
    "session": session,
    "case": case,
}
target.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

  outcome="$(python3 - "$record_json" <<'PY'
import json, sys
print(json.load(open(sys.argv[1])).get("outcome", "fail"))
PY
)"
  reason="$(python3 - "$record_json" <<'PY'
import json, sys
print(json.load(open(sys.argv[1])).get("reason", "manual medium soak observation recorded"))
PY
)"
  stdout_payload="$(python3 - "$record_json" <<'PY'
import json, sys
print(json.dumps(json.load(open(sys.argv[1])), indent=2, sort_keys=True))
PY
)"

  "$CASE_RUNNER" mark \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "SOAK-002" \
    --name "mixed-pack-medium-soak" \
    --outcome "$outcome" \
    --reason "$reason" \
    --command "manual medium mixed-pack soak observation from $OBS_FILE" \
    --stdout "$stdout_payload"

  write_medium_soak_report "$OUT_ROOT/SOAK-002" "$record_json"
  rm -f "$record_json"
}

if [[ "$RUN_SOAK" != "true" ]]; then
  "$CASE_RUNNER" mark \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "SOAK-001" \
    --name "short-dedicated-server-soak" \
    --outcome "not-run" \
    --reason "safe default: set INTERMED_RUN_SOAK=true to run :app:runtimeSoak"

  "$CASE_RUNNER" mark \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "SOAK-002" \
    --name "mixed-pack-medium-soak" \
    --outcome "not-run" \
    --reason "medium mixed-pack soak is semi-automated and not enabled by default"

  "$CASE_RUNNER" mark \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "SOAK-003" \
    --name "long-soak" \
    --outcome "unsupported" \
    --reason "long soak is explicitly deferred until later stable-prep stage"

  "$ROOT_DIR/testing/collect_artifacts.sh" "$SUITE" "$OUT_ROOT"
  exit 0
fi

"$CASE_RUNNER" run \
  --suite "$SUITE" \
  --out-root "$OUT_ROOT" \
  --case-id "SOAK-001" \
  --name "short-dedicated-server-soak" \
  --success-reason "in-repo runtime soak lane completed" \
  --command "cd '$ROOT_DIR' && '$GRADLE_BIN' :app:runtimeSoak --rerun-tasks --no-daemon --max-workers=1 -Dintermed.allowRemoteForgeRepo=true --console=plain" || true

if [[ "$RUN_MEDIUM" == "true" ]]; then
  write_medium_soak_template
  if [[ -n "$OBS_FILE" && -f "$OBS_FILE" ]]; then
    mark_medium_from_observation
  else
    "$CASE_RUNNER" mark \
      --suite "$SUITE" \
      --out-root "$OUT_ROOT" \
      --case-id "SOAK-002" \
      --name "mixed-pack-medium-soak" \
      --outcome "not-run" \
      --reason "manual medium mixed-pack soak checklist prepared at $MEDIUM_CHECKLIST; rerun with INTERMED_SOAK_OBSERVATIONS=$MEDIUM_OBS_TEMPLATE" \
      --command "manual medium mixed-pack soak checklist in $MEDIUM_SESSION_ROOT" \
      --stdout "Checklist: $MEDIUM_CHECKLIST"$'\n'"Observation template: $MEDIUM_OBS_TEMPLATE"
    cp "$MEDIUM_OBS_TEMPLATE" "$OUT_ROOT/SOAK-002/observation-template.json"
    cp "$MEDIUM_CHECKLIST" "$OUT_ROOT/SOAK-002/manual-checklist.md"
  fi
else
  "$CASE_RUNNER" mark \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "SOAK-002" \
    --name "mixed-pack-medium-soak" \
    --outcome "not-run" \
    --reason "medium mixed-pack soak is semi-automated and not enabled by default"
fi

"$CASE_RUNNER" mark \
  --suite "$SUITE" \
  --out-root "$OUT_ROOT" \
  --case-id "SOAK-003" \
  --name "long-soak" \
  --outcome "unsupported" \
  --reason "long soak is explicitly deferred until later stable-prep stage"

"$ROOT_DIR/testing/collect_artifacts.sh" "$SUITE" "$OUT_ROOT"
