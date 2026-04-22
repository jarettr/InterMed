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

mkdir -p "$OUT_ROOT"

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
  "$CASE_RUNNER" mark \
    --suite "$SUITE" \
    --out-root "$OUT_ROOT" \
    --case-id "SOAK-002" \
    --name "mixed-pack-medium-soak" \
    --outcome "blocked" \
    --reason "medium mixed-pack soak was requested, but the external runner is not wired yet"
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
