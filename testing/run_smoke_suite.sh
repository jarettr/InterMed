#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DATE="${INTERMED_TEST_DATE:-$(date -u +%Y%m%d)}"
SUITE="${INTERMED_TEST_SUITE:-nightly-smoke}"
OUT_ROOT="${INTERMED_TEST_RUN_ROOT:-$ROOT_DIR/build/test-runs/$RUN_DATE/$SUITE}"
JAVA_BIN="${JAVA_BIN:-${INTERMED_JAVA:-java}}"
GRADLE_BIN="${GRADLE_BIN:-$ROOT_DIR/gradlew}"
CASE_RUNNER="$ROOT_DIR/testing/_case_runner.py"
RUN_SMOKE_SUITE="${INTERMED_RUN_SMOKE_SUITE:-false}"
DEDICATED_BOOT_RUNNER="$ROOT_DIR/testing/run_dedicated_boot_case.sh"
INTERMED_VERSION="$(grep '^intermedVersion=' "$ROOT_DIR/gradle.properties" | cut -d= -f2)"
CORE_JAR="$ROOT_DIR/app/build/libs/InterMedCore-$INTERMED_VERSION.jar"
FABRIC_JAR="$ROOT_DIR/app/build/libs/InterMedCore-$INTERMED_VERSION-fabric.jar"
JAVA_BIN_PATH="$(command -v "$JAVA_BIN" 2>/dev/null || true)"
if [[ -n "$JAVA_BIN_PATH" ]]; then
  DEFAULT_JAR_BIN="$(cd "$(dirname "$JAVA_BIN_PATH")" && pwd)/jar"
else
  DEFAULT_JAR_BIN="$(command -v jar 2>/dev/null || true)"
fi
JAR_BIN="${INTERMED_JAR_BIN:-$DEFAULT_JAR_BIN}"

mkdir -p "$OUT_ROOT"

case_run() {
  "$CASE_RUNNER" run --suite "$SUITE" --out-root "$OUT_ROOT" "$@"
}

case_mark() {
  "$CASE_RUNNER" mark --suite "$SUITE" --out-root "$OUT_ROOT" "$@"
}

gradle_case() {
  local case_id="$1"
  local name="$2"
  local tests="$3"
  if ! case_run \
    --case-id "$case_id" \
    --name "$name" \
    --command "cd '$ROOT_DIR' && '$GRADLE_BIN' :app:test --tests '$tests' -Dintermed.allowRemoteForgeRepo=true --console=plain" \
    --success-reason "synthetic fixture tests passed for $name"; then
    return 1
  fi
  write_smoke_support_artifacts "$case_id" "$name" "$tests"
}

write_support_artifact_from_result() {
  local case_id="$1"
  local artifact_name="$2"
  local schema="$3"
  local kind="$4"
  local note="$5"
  local tests="$6"
  local result_path="$OUT_ROOT/$case_id/result.json"
  local artifact_path="$OUT_ROOT/$case_id/$artifact_name"

  if [[ ! -f "$result_path" ]]; then
    return 0
  fi

  python3 - "$result_path" "$artifact_path" "$schema" "$kind" "$note" "$tests" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

result_path = Path(sys.argv[1])
artifact_path = Path(sys.argv[2])
schema = sys.argv[3]
kind = sys.argv[4]
note = sys.argv[5]
tests = sys.argv[6]
result = json.loads(result_path.read_text(encoding="utf-8"))
if result.get("outcome") != "pass":
    raise SystemExit(0)
payload = {
    "schema": schema,
    "case_id": result.get("case_id"),
    "kind": kind,
    "outcome": result.get("outcome"),
    "reason": result.get("reason", ""),
    "evidence_level": "synthetic-tested",
    "source": "Gradle :app:test synthetic fixture lane",
    "source_tests": tests,
    "note": note,
    "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "limitations": [
        "This artifact records synthetic fixture evidence.",
        "It does not replace required real client/server, external corpus, native, performance, or soak evidence.",
    ],
}
artifact_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

write_smoke_support_artifacts() {
  local case_id="$1"
  local name="$2"
  local tests="$3"

  case "$case_id" in
    DAG-001|DAG-002|DAG-003|DAG-004|DAG-005)
      write_support_artifact_from_result \
        "$case_id" \
        "dependency-plan.json" \
        "intermed-synthetic-dependency-evidence-v1" \
        "dependency-plan" \
        "Synthetic resolver/classloader evidence for $name." \
        "$tests"
      ;;
    MIXIN-002)
      write_support_artifact_from_result \
        "$case_id" \
        "mixin-conflict-report.json" \
        "intermed-synthetic-mixin-conflict-report-v1" \
        "mixin-conflict-report" \
        "Synthetic AST/mixin conflict-policy evidence for $name." \
        "$tests"
      ;;
    REG-001|REG-002|REG-003)
      write_support_artifact_from_result \
        "$case_id" \
        "registry-report.json" \
        "intermed-synthetic-registry-report-v1" \
        "registry-report" \
        "Synthetic registry mediation evidence for $name." \
        "$tests"
      ;;
    VFS-001|VFS-005)
      write_support_artifact_from_result \
        "$case_id" \
        "vfs-diagnostics.json" \
        "intermed-synthetic-vfs-diagnostics-v1" \
        "vfs-diagnostics" \
        "Synthetic VFS overlay and merge-policy evidence for $name." \
        "$tests"
      ;;
  esac
}

metadata_not_beta_pass() {
  local case_id="$1"
  local name="$2"
  local mods_dir="$3"
  local report="$OUT_ROOT/$case_id/compatibility-report.json"
  mkdir -p "$OUT_ROOT/$case_id/game" "$mods_dir"
  case_run \
    --case-id "$case_id" \
    --name "$name" \
    --mods-dir "$mods_dir" \
    --require-file "compatibility-report.json" \
    --success-outcome "not-run" \
    --success-reason "metadata compatibility smoke was captured, but the required dedicated server scenario was not executed" \
    --command "'$JAVA_BIN' -jar '$CORE_JAR' compat-smoke --agent '$CORE_JAR' --game-dir '$OUT_ROOT/$case_id/game' --mods-dir '$mods_dir' --report '$report'"
}

resolve_server_base() {
  local loader="$1"
  local explicit=""
  local search_name=""
  local preferred=()
  case "$loader" in
    fabric)
      explicit="${INTERMED_SERVER_BASE_FABRIC:-}"
      search_name="server-base-fabric"
      preferred=(
        "$ROOT_DIR/build/test-runs/$RUN_DATE/nightly-corpus-real-small/CORPUS-001/harness-output/cache/server-base-fabric"
        "$ROOT_DIR/build/test-runs/$RUN_DATE/nightly-corpus-fabric-pairs-small/CORPUS-002/harness-output/cache/server-base-fabric"
      )
      ;;
    forge)
      explicit="${INTERMED_SERVER_BASE_FORGE:-}"
      search_name="server-base-forge"
      preferred=(
        "$ROOT_DIR/build/test-runs/$RUN_DATE/harness-forge-boot/raw/cache/server-base-forge"
        "$ROOT_DIR/build/test-runs/$RUN_DATE/nightly-corpus-real-small/CORPUS-001/harness-output/cache/server-base-forge"
      )
      ;;
    neoforge)
      explicit="${INTERMED_SERVER_BASE_NEOFORGE:-}"
      search_name="server-base-neoforge"
      preferred=(
        "$ROOT_DIR/build/test-runs/$RUN_DATE/harness-neoforge-boot/raw/cache/server-base-neoforge"
        "$ROOT_DIR/build/test-runs/$RUN_DATE/nightly-corpus-real-small/CORPUS-001/harness-output/cache/server-base-neoforge"
      )
      ;;
    *)
      return 1
      ;;
  esac

  if [[ -n "$explicit" && -d "$explicit" ]]; then
    printf '%s\n' "$explicit"
    return 0
  fi

  local candidate
  for candidate in "${preferred[@]}"; do
    if [[ -d "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  candidate="$(find "$ROOT_DIR/build/test-runs/$RUN_DATE" -type d -name "$search_name" | sort | head -n 1)"
  if [[ -n "$candidate" && -d "$candidate" ]]; then
    printf '%s\n' "$candidate"
    return 0
  fi
  return 1
}

boot_case() {
  local case_id="$1"
  local name="$2"
  local loader="$3"
  local mods_dir="$4"
  local dependency_plan="${5:-false}"
  local agent_jar="$CORE_JAR"
  local base_dir=""
  local command=""

  if [[ "$loader" == "fabric" ]]; then
    agent_jar="$FABRIC_JAR"
  fi

  if ! base_dir="$(resolve_server_base "$loader")"; then
    case_mark \
      --case-id "$case_id" \
      --name "$name" \
      --mods-dir "$mods_dir" \
      --outcome "blocked" \
      --reason "dedicated server base cache for $loader is missing; bootstrap a harness cache or set INTERMED_SERVER_BASE_${loader^^}"
    return 0
  fi

  command="cd '$ROOT_DIR' && '$DEDICATED_BOOT_RUNNER' --loader '$loader' --agent-jar '$agent_jar' --base-dir '$base_dir' --case-dir '$OUT_ROOT/$case_id' --mods-source-dir '$mods_dir' --java-bin '$JAVA_BIN' --timeout-seconds 180 --heap-mb 1536"
  if [[ "$dependency_plan" == "true" ]]; then
    command+=" --write-dependency-plan"
  fi

  if [[ "$dependency_plan" == "true" ]]; then
    case_run \
      --case-id "$case_id" \
      --name "$name" \
      --mods-dir "$mods_dir" \
      --require-file "startup-report.json" \
      --require-file "dependency-plan.json" \
      --command "$command" \
      --success-reason "dedicated server reached ready state for $name and emitted a minimal dependency plan"
  else
    case_run \
      --case-id "$case_id" \
      --name "$name" \
      --mods-dir "$mods_dir" \
      --require-file "startup-report.json" \
      --command "$command" \
      --success-reason "dedicated server reached ready state for $name"
  fi
}

create_fixture_mods() {
  local fixtures="$OUT_ROOT/_fixtures"
  if [[ -z "$JAR_BIN" || ! -x "$JAR_BIN" ]]; then
    echo "[smoke-suite] jar tool not found; set INTERMED_JAR_BIN or JAVA_BIN to a JDK 21 installation" >&2
    return 1
  fi
  rm -rf "$fixtures"
  mkdir -p "$fixtures/fabric/resources" "$fixtures/forge/resources/META-INF" "$fixtures/neoforge/resources/META-INF"

  cat > "$fixtures/fabric/resources/fabric.mod.json" <<'JSON'
{
  "schemaVersion": 1,
  "id": "smoke_fabric",
  "version": "1.0.0",
  "name": "Smoke Fabric Fixture",
  "environment": "*",
  "depends": {
    "minecraft": "1.20.1"
  }
}
JSON
  "$JAR_BIN" --create --file "$fixtures/smoke-fabric-1.0.0.jar" -C "$fixtures/fabric/resources" .

  cat > "$fixtures/forge/resources/META-INF/mods.toml" <<'TOML'
modLoader="javafml"
loaderVersion="[47,)"
license="MIT"

[[mods]]
modId="smoke_forge"
version="1.0.0"
displayName="Smoke Forge Fixture"
description="Minimal Forge metadata fixture for InterMed beta-prep smoke."
TOML
  "$JAR_BIN" --create --file "$fixtures/smoke-forge-1.0.0.jar" -C "$fixtures/forge/resources" .

  cat > "$fixtures/neoforge/resources/META-INF/neoforge.mods.toml" <<'TOML'
modLoader="javafml"
loaderVersion="[47,)"
license="MIT"

[[mods]]
modId="smoke_neoforge"
version="1.0.0"
displayName="Smoke NeoForge Fixture"
description="Minimal NeoForge metadata fixture for InterMed beta-prep smoke."
TOML
  "$JAR_BIN" --create --file "$fixtures/smoke-neoforge-1.0.0.jar" -C "$fixtures/neoforge/resources" .
}

if [[ "$RUN_SMOKE_SUITE" != "true" ]]; then
  for case_id in BOOT-001 BOOT-002 BOOT-003 BOOT-004 DAG-001 DAG-002 DAG-003 DAG-004 DAG-005 REMAP-001 REMAP-002 REMAP-003 MIXIN-001 MIXIN-002 MIXIN-003 MIXIN-004 MIXIN-005 REG-001 REG-002 REG-003 REG-004 VFS-001 VFS-002 VFS-003 VFS-004 VFS-005; do
    case_mark \
      --case-id "$case_id" \
      --name "$case_id" \
      --outcome "not-run" \
      --reason "safe default: set INTERMED_RUN_SMOKE_SUITE=true to run Gradle-backed smoke accounting"
  done
  "$ROOT_DIR/testing/collect_artifacts.sh" "$SUITE" "$OUT_ROOT"
  exit 0
fi

if ! "$GRADLE_BIN" :app:coreJar :app:coreFabricJar :app:bootstrapJar --console=plain > "$OUT_ROOT/setup-gradle.stdout.log" 2> "$OUT_ROOT/setup-gradle.stderr.log"; then
  for case_id in BOOT-001 BOOT-002 BOOT-003 BOOT-004 DAG-001 DAG-002 DAG-003 DAG-004 DAG-005 REMAP-001 REMAP-002 REMAP-003 MIXIN-001 MIXIN-002 MIXIN-003 MIXIN-004 MIXIN-005 REG-001 REG-002 REG-003 REG-004 VFS-001 VFS-002 VFS-003 VFS-004 VFS-005; do
    case_mark \
      --case-id "$case_id" \
      --name "$case_id" \
      --outcome "blocked" \
      --reason "smoke suite setup build failed; see setup-gradle logs"
  done
  "$ROOT_DIR/testing/collect_artifacts.sh" "$SUITE" "$OUT_ROOT"
  exit 1
fi
if ! create_fixture_mods; then
  for case_id in BOOT-001 BOOT-002 BOOT-003 BOOT-004 DAG-001 DAG-002 DAG-003 DAG-004 DAG-005 REMAP-001 REMAP-002 REMAP-003 MIXIN-001 MIXIN-002 MIXIN-003 MIXIN-004 MIXIN-005 REG-001 REG-002 REG-003 REG-004 VFS-001 VFS-002 VFS-003 VFS-004 VFS-005; do
    case_mark \
      --case-id "$case_id" \
      --name "$case_id" \
      --outcome "blocked" \
      --reason "smoke suite fixture pack creation failed; check JDK jar tool and setup logs"
  done
  "$ROOT_DIR/testing/collect_artifacts.sh" "$SUITE" "$OUT_ROOT"
  exit 1
fi

mkdir -p "$OUT_ROOT/BOOT-001/mods" "$OUT_ROOT/BOOT-002/mods" "$OUT_ROOT/BOOT-003/mods" "$OUT_ROOT/BOOT-004/mods"
cp "$OUT_ROOT/_fixtures/smoke-fabric-1.0.0.jar" "$OUT_ROOT/BOOT-001/mods/"
cp "$OUT_ROOT/_fixtures/smoke-forge-1.0.0.jar" "$OUT_ROOT/BOOT-002/mods/"
cp "$OUT_ROOT/_fixtures/smoke-neoforge-1.0.0.jar" "$OUT_ROOT/BOOT-003/mods/"
cp "$OUT_ROOT/_fixtures/"*.jar "$OUT_ROOT/BOOT-004/mods/"

boot_case "BOOT-001" "minimal-fabric-dedicated-server" "fabric" "$OUT_ROOT/BOOT-001/mods" || true
boot_case "BOOT-002" "minimal-forge-dedicated-server" "forge" "$OUT_ROOT/BOOT-002/mods" || true
boot_case "BOOT-003" "minimal-neoforge-dedicated-server" "neoforge" "$OUT_ROOT/BOOT-003/mods" || true
boot_case "BOOT-004" "minimal-mixed-loader-dedicated-server" "forge" "$OUT_ROOT/BOOT-004/mods" "true" || true

gradle_case "DAG-001" "deterministic-dependency-plan" "org.intermed.core.resolver.*" || true
gradle_case "DAG-002" "parent-peer-weak-peer-wiring" "org.intermed.core.classloading.LazyInterMedClassLoaderTest" || true
gradle_case "DAG-003" "private-nested-library-isolation" "org.intermed.core.classloading.LibraryDiscoveryTest" || true
gradle_case "DAG-004" "private-library-re-export" "org.intermed.core.classloading.LazyInterMedClassLoaderTest" || true
gradle_case "DAG-005" "fallback-discipline" "org.intermed.core.lifecycle.LifecycleManagerIntegrationTest" || true

gradle_case "REMAP-001" "bytecode-class-member-remap" "org.intermed.core.remapping.*" || true
gradle_case "REMAP-002" "reflection-string-remap" "org.intermed.core.remapping.*" || true
case_mark --case-id "REMAP-003" --name "unsupported-dynamic-name-diagnostics" --outcome "not-run" --reason "dataflow edge-path diagnostics require a dedicated beta fixture"

gradle_case "MIXIN-001" "additive-inject-merge" "org.intermed.core.mixin.*" || true
gradle_case "MIXIN-002" "overwrite-conflict-policy" "org.intermed.core.ast.ResolutionEngineTest" || true
gradle_case "MIXIN-003" "redirect-wrap-chain-order" "org.intermed.core.mixin.*" || true
case_mark --case-id "MIXIN-004" --name "public-mod-mixin-corpus-classification" --outcome "not-run" --reason "public mod mixin corpus is handled by testing/run_corpus_shard.sh"
case_mark --case-id "MIXIN-005" --name "safe-fail-for-unsupported-features" --outcome "not-run" --reason "unsupported public mixin feature fixtures are not frozen yet"

gradle_case "REG-001" "conflicting-key-sharding" "org.intermed.core.registry.VirtualRegistryServiceTest" || true
gradle_case "REG-002" "global-id-lookup-consistency" "org.intermed.core.registry.*" || true
gradle_case "REG-003" "registry-freeze-behavior" "org.intermed.core.registry.*" || true
case_mark --case-id "REG-004" --name "mixed-pack-registry-sync" --outcome "not-run" --reason "real client/server registry sync is handled by testing/run_client_server_smoke.sh"

gradle_case "VFS-001" "resource-overlay-conflict-diagnostics" "org.intermed.core.vfs.VirtualFileSystemRouterTest" || true
case_mark --case-id "VFS-002" --name "recipe-reload" --outcome "not-run" --reason "real datapack reload lifecycle is not automated yet"
case_mark --case-id "VFS-003" --name "tag-reload" --outcome "not-run" --reason "real datapack reload lifecycle is not automated yet"
case_mark --case-id "VFS-004" --name "loot-table-reload" --outcome "not-run" --reason "real datapack reload lifecycle is not automated yet"
gradle_case "VFS-005" "priority-override-merge-policy-sanity" "org.intermed.core.vfs.VirtualFileSystemRouterTest" || true

"$ROOT_DIR/testing/collect_artifacts.sh" "$SUITE" "$OUT_ROOT"
