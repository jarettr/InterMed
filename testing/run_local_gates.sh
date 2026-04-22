#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DATE="${INTERMED_TEST_DATE:-$(date -u +%Y%m%d)}"
SUITE="${INTERMED_TEST_SUITE:-ci-core}"
OUT_ROOT="${INTERMED_TEST_RUN_ROOT:-$ROOT_DIR/build/test-runs/$RUN_DATE/$SUITE}"
JAVA_BIN="${JAVA_BIN:-java}"
GRADLE_BIN="${GRADLE_BIN:-$ROOT_DIR/gradlew}"
RUN_LOCAL_GATES="${INTERMED_RUN_LOCAL_GATES:-false}"
export JAVA_BIN

mkdir -p "$OUT_ROOT"

json_write() {
  local path="$1"
  local payload="$2"
  python3 - "$path" "$payload" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(sys.argv[2])
path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

common_artifacts() {
  local case_dir="$1"
  local case_id="$2"
  local case_name="$3"
  local command_text="$4"
  local started_at="$5"

  printf '%s\n' "$command_text" > "$case_dir/command.txt"

  local commit
  commit="$(git -C "$ROOT_DIR" rev-parse HEAD 2>/dev/null || printf 'unknown')"

  json_write "$case_dir/run-manifest.json" "$(
    python3 -c 'import json, os, sys; print(json.dumps({
      "schema": "intermed-run-manifest-v1",
      "case_id": sys.argv[1],
      "name": sys.argv[2],
      "suite": sys.argv[3],
      "started_at": sys.argv[4],
      "commit": sys.argv[5],
      "artifact_contract": "docs/test-plan-v8-alpha-to-beta.md"
    }))' "$case_id" "$case_name" "$SUITE" "$started_at" "$commit"
  )"

  "$JAVA_BIN" -version > "$case_dir/java-version.txt" 2>&1 || true
  git -C "$ROOT_DIR" status --short > "$case_dir/git-status.txt" 2>/dev/null || true

  python3 - "$case_dir/environment.json" "$ROOT_DIR" "$commit" <<'PY'
import json
import os
import platform
import subprocess
import sys
from pathlib import Path

path = Path(sys.argv[1])
root = Path(sys.argv[2])
commit = sys.argv[3]

try:
    java_version = subprocess.run(
        [os.environ.get("JAVA_BIN", "java"), "-version"],
        cwd=root,
        text=True,
        capture_output=True,
        check=False,
    ).stderr.strip().splitlines()
except Exception as exc:
    java_version = [f"java-version-error: {exc}"]

payload = {
    "schema": "intermed-environment-v1",
    "env_id": os.environ.get("INTERMED_ENV_ID", "unknown"),
    "machine_class": os.environ.get("INTERMED_MACHINE_CLASS", "unknown"),
    "minecraft": "1.20.1",
    "java_baseline": "21",
    "os": {
        "system": platform.system(),
        "release": platform.release(),
        "machine": platform.machine(),
    },
    "java_version": java_version,
    "commit": commit,
}
path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

  json_write "$case_dir/mod-list.json" '{"schema":"intermed-mod-list-v1","mods":[]}'
}

finish_case() {
  local case_dir="$1"
  local case_id="$2"
  local case_name="$3"
  local exit_code="$4"
  local outcome="$5"
  local reason="$6"
  local started_at="$7"
  local ended_at="$8"

  json_write "$case_dir/exit-status.json" "$(
    python3 -c 'import json, sys; print(json.dumps({
      "schema": "intermed-exit-status-v1",
      "exit_code": int(sys.argv[1]),
      "started_at": sys.argv[2],
      "ended_at": sys.argv[3]
    }))' "$exit_code" "$started_at" "$ended_at"
  )"

  json_write "$case_dir/result.json" "$(
    python3 -c 'import json, sys; print(json.dumps({
      "schema": "intermed-case-result-v1",
      "case_id": sys.argv[1],
      "name": sys.argv[2],
      "outcome": sys.argv[3],
      "reason": sys.argv[4],
      "exit_code": int(sys.argv[5])
    }))' "$case_id" "$case_name" "$outcome" "$reason" "$exit_code"
  )"
}

run_case_shell() {
  local case_id="$1"
  local case_name="$2"
  local command_text="$3"
  local case_dir="$OUT_ROOT/$case_id"
  local started_at ended_at exit_code outcome reason

  mkdir -p "$case_dir"
  started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  common_artifacts "$case_dir" "$case_id" "$case_name" "$command_text" "$started_at"

  set +e
  bash -lc "$command_text" > "$case_dir/stdout.log" 2> "$case_dir/stderr.log"
  exit_code=$?
  set -e

  ended_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [[ "$exit_code" -eq 0 ]]; then
    outcome="pass"
    reason="command completed successfully"
  else
    outcome="fail"
    reason="command exited non-zero"
  fi

  finish_case "$case_dir" "$case_id" "$case_name" "$exit_code" "$outcome" "$reason" "$started_at" "$ended_at"

  if [[ "$case_id" == "GATE-001" || "$case_id" == "GATE-002" ]]; then
    {
      printf 'case_id=%s\n' "$case_id"
      printf 'exit_code=%s\n' "$exit_code"
      printf 'outcome=%s\n' "$outcome"
      printf 'reason=%s\n' "$reason"
    } > "$case_dir/gradle-summary.txt"
  fi
}

run_forced_failure_case() {
  local case_id="GATE-004"
  local case_name="diagnostics-bundle-on-forced-failure"
  local case_dir="$OUT_ROOT/$case_id"
  local core_jar="$ROOT_DIR/app/build/libs/InterMedCore-$(grep '^intermedVersion=' "$ROOT_DIR/gradle.properties" | cut -d= -f2).jar"
  local game_dir="$case_dir/game"
  local mods_dir="$game_dir/intermed_mods"
  local diag="$case_dir/diagnostics-bundle.zip"
  local command_text started_at ended_at exit_code outcome reason

  mkdir -p "$case_dir" "$mods_dir"
  command_text="$JAVA_BIN -jar $core_jar launch --agent $core_jar --game-dir $game_dir --mods-dir $mods_dir --main-class definitely.missing.Main --classpath $case_dir/missing.jar --diagnostics-output $diag -- --forced-failure"
  started_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  common_artifacts "$case_dir" "$case_id" "$case_name" "$command_text" "$started_at"

  set +e
  "$JAVA_BIN" -jar "$core_jar" launch \
    --agent "$core_jar" \
    --game-dir "$game_dir" \
    --mods-dir "$mods_dir" \
    --main-class definitely.missing.Main \
    --classpath "$case_dir/missing.jar" \
    --diagnostics-output "$diag" \
    -- --forced-failure > "$case_dir/stdout.log" 2> "$case_dir/stderr.log"
  exit_code=$?
  set -e

  ended_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  if [[ "$exit_code" -ne 0 && -s "$diag" ]]; then
    outcome="pass"
    reason="intentional failed launch produced diagnostics-bundle.zip"
  else
    outcome="fail"
    reason="expected non-zero launch and diagnostics-bundle.zip"
  fi

  finish_case "$case_dir" "$case_id" "$case_name" "$exit_code" "$outcome" "$reason" "$started_at" "$ended_at"
}

CORE_VERSION="$(grep '^intermedVersion=' "$ROOT_DIR/gradle.properties" | cut -d= -f2)"
CORE_JAR="$ROOT_DIR/app/build/libs/InterMedCore-$CORE_VERSION.jar"
FABRIC_JAR="$ROOT_DIR/app/build/libs/InterMedCore-$CORE_VERSION-fabric.jar"

if [[ "$RUN_LOCAL_GATES" != "true" ]]; then
  "$ROOT_DIR/testing/_case_runner.py" mark \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "GATE-001" \
    --name "clean-checkout-hard-gate" \
    --outcome "not-run" \
    --reason "safe default: set INTERMED_RUN_LOCAL_GATES=true to run Gradle hard gates"

  "$ROOT_DIR/testing/_case_runner.py" mark \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "GATE-002" \
    --name "harness-self-test" \
    --outcome "not-run" \
    --reason "safe default: set INTERMED_RUN_LOCAL_GATES=true to run harness tests"

  "$ROOT_DIR/testing/_case_runner.py" mark \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "GATE-003" \
    --name "launch-kit-generation" \
    --outcome "not-run" \
    --reason "safe default: set INTERMED_RUN_LOCAL_GATES=true to build release jars and generate launch-kit"

  "$ROOT_DIR/testing/_case_runner.py" mark \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "GATE-004" \
    --name "diagnostics-bundle-on-forced-failure" \
    --outcome "not-run" \
    --reason "safe default: set INTERMED_RUN_LOCAL_GATES=true to run forced-failure diagnostics"

  "$ROOT_DIR/testing/collect_artifacts.sh" "$SUITE" "$OUT_ROOT" > "$OUT_ROOT/summary-path.txt"
  printf 'Wrote suite summary: %s\n' "$(cat "$OUT_ROOT/summary-path.txt")"
  exit 0
fi

run_case_shell \
  "GATE-001" \
  "clean-checkout-hard-gate" \
  "cd '$ROOT_DIR' && test -z \"\$(git status --porcelain --untracked-files=normal)\" && '$GRADLE_BIN' :app:test :app:coverageGate :app:strictSecurity :app:verifyRuntime --rerun-tasks -Dintermed.allowRemoteForgeRepo=true --console=plain"

run_case_shell \
  "GATE-002" \
  "harness-self-test" \
  "cd '$ROOT_DIR' && '$GRADLE_BIN' :test-harness:test --rerun-tasks --console=plain"

run_case_shell \
  "GATE-003" \
  "launch-kit-generation" \
  "cd '$ROOT_DIR' && '$GRADLE_BIN' :app:coreJar :app:coreFabricJar :app:bootstrapJar --console=plain && mkdir -p '$OUT_ROOT/GATE-003/game/intermed_mods' && '$JAVA_BIN' -jar '$CORE_JAR' launch-kit --agent '$CORE_JAR' --fabric-agent '$FABRIC_JAR' --game-dir '$OUT_ROOT/GATE-003/game' --mods-dir '$OUT_ROOT/GATE-003/game/intermed_mods' --output-dir '$OUT_ROOT/GATE-003/launch-kit' && test -s '$OUT_ROOT/GATE-003/launch-kit/intermed-launch-kit.json' && test -s '$OUT_ROOT/GATE-003/launch-kit/launcher-jvm-args-generic.txt' && test -s '$OUT_ROOT/GATE-003/launch-kit/intermed-launch-generic.sh'"

run_forced_failure_case

"$ROOT_DIR/testing/collect_artifacts.sh" "$SUITE" "$OUT_ROOT" > "$OUT_ROOT/summary-path.txt"
printf 'Wrote suite summary: %s\n' "$(cat "$OUT_ROOT/summary-path.txt")"
