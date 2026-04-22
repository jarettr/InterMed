#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DATE="${INTERMED_TEST_DATE:-$(date -u +%Y%m%d)}"
NIGHTLY_SUITE="nightly-smoke"
WEEKLY_SUITE="weekly-soak"
RUN_ROOT="${INTERMED_TEST_RUN_ROOT:-$ROOT_DIR/build/test-runs/$RUN_DATE}"
NIGHTLY_OUT="${INTERMED_CLIENT_SERVER_NIGHTLY_OUT:-$RUN_ROOT/$NIGHTLY_SUITE}"
WEEKLY_OUT="${INTERMED_CLIENT_SERVER_WEEKLY_OUT:-$RUN_ROOT/$WEEKLY_SUITE}"
SESSION_ROOT="${INTERMED_CLIENT_SERVER_SESSION_ROOT:-$RUN_ROOT/manual-client-server-smoke}"
CASE_RUNNER="$ROOT_DIR/testing/_case_runner.py"
COLLECTOR="$ROOT_DIR/testing/collect_artifacts.sh"
DEFAULT_INTERMED_JAVA="/home/mak/.local/jdks/temurin21-full/jdk-21.0.10+7/bin/java"
if [[ -x "$DEFAULT_INTERMED_JAVA" ]]; then
  DEFAULT_JAVA_BIN="$DEFAULT_INTERMED_JAVA"
else
  DEFAULT_JAVA_BIN="java"
fi
JAVA_BIN="${JAVA_BIN:-${INTERMED_JAVA:-$DEFAULT_JAVA_BIN}}"
GRADLE_BIN="${GRADLE_BIN:-$ROOT_DIR/gradlew}"
RUN_CLIENT_SERVER="${INTERMED_RUN_CLIENT_SERVER_SMOKE:-false}"
OBS_FILE="${INTERMED_CLIENT_SERVER_OBSERVATIONS:-}"
SERVER_PORT="${INTERMED_CLIENT_SERVER_PORT:-25565}"
INTERMED_VERSION="$(grep '^intermedVersion=' "$ROOT_DIR/gradle.properties" | cut -d= -f2)"
CORE_JAR="$ROOT_DIR/app/build/libs/InterMedCore-$INTERMED_VERSION.jar"
FABRIC_JAR="$ROOT_DIR/app/build/libs/InterMedCore-$INTERMED_VERSION-fabric.jar"
BOOTSTRAP_JAR="$ROOT_DIR/app/build/libs/InterMedCore-$INTERMED_VERSION-bootstrap.jar"
SHARED_MODS_DIR="$SESSION_ROOT/shared-mods"
SERVER_GAME_DIR="$SESSION_ROOT/server-game"
CLIENT_GAME_DIR="$SESSION_ROOT/client-game"
CLIENT_LAUNCH_KIT_DIR="$SESSION_ROOT/client-launch-kit"
OBS_TEMPLATE="$SESSION_ROOT/observation-template.json"
CHECKLIST_PATH="$SESSION_ROOT/manual-checklist.md"
SESSION_MANIFEST="$SESSION_ROOT/session-manifest.json"

JAVA_BIN_PATH="$(command -v "$JAVA_BIN" 2>/dev/null || true)"
if [[ -n "$JAVA_BIN_PATH" ]]; then
  DEFAULT_JAR_BIN="$(cd "$(dirname "$JAVA_BIN_PATH")" && pwd)/jar"
else
  DEFAULT_JAR_BIN="$(command -v jar 2>/dev/null || true)"
fi
JAR_BIN="${INTERMED_JAR_BIN:-$DEFAULT_JAR_BIN}"

mkdir -p "$NIGHTLY_OUT" "$WEEKLY_OUT" "$SESSION_ROOT"

all_cases=(
  "BOOT-005"
  "REG-004"
  "NET-001"
  "NET-002"
  "NET-003"
  "NET-004"
  "NET-005"
  "VFS-002"
  "VFS-003"
  "VFS-004"
)

case_name() {
  case "$1" in
    BOOT-005) echo "client-smoke-launch" ;;
    REG-004) echo "mixed-pack-registry-sync" ;;
    NET-001) echo "login-handshake" ;;
    NET-002) echo "registry-sync-during-login" ;;
    NET-003) echo "custom-payload-roundtrip" ;;
    NET-004) echo "short-play-session-smoke" ;;
    NET-005) echo "disconnect-reconnect-stability" ;;
    VFS-002) echo "recipe-reload" ;;
    VFS-003) echo "tag-reload" ;;
    VFS-004) echo "loot-table-reload" ;;
    *) echo "$1" ;;
  esac
}

case_suite() {
  case "$1" in
    NET-004|NET-005) echo "$WEEKLY_SUITE" ;;
    *) echo "$NIGHTLY_SUITE" ;;
  esac
}

case_out_root() {
  case "$(case_suite "$1")" in
    "$WEEKLY_SUITE") printf '%s\n' "$WEEKLY_OUT" ;;
    *) printf '%s\n' "$NIGHTLY_OUT" ;;
  esac
}

case_dir() {
  printf '%s/%s\n' "$(case_out_root "$1")" "$1"
}

existing_outcome() {
  local case_id="$1"
  local result_path
  result_path="$(case_dir "$case_id")/result.json"
  if [[ ! -f "$result_path" ]]; then
    return 1
  fi
  python3 - "$result_path" <<'PY'
import json
import sys
from pathlib import Path

path = Path(sys.argv[1])
payload = json.loads(path.read_text(encoding="utf-8"))
print(payload.get("outcome", "unknown"))
PY
}

mark_case() {
  local case_id="$1"
  local outcome="$2"
  local reason="$3"
  local stdout_payload="${4:-}"
  local stderr_payload="${5:-}"
  local mods_dir="${6:-$SHARED_MODS_DIR}"
  local existing=""
  local suite=""
  local out_root=""
  local name=""

  if existing="$(existing_outcome "$case_id" 2>/dev/null)"; then
    if [[ "$outcome" == "not-run" || "$outcome" == "blocked" ]]; then
      case "$existing" in
        pass|fail|unsupported)
          return 0
          ;;
      esac
    fi
  fi

  suite="$(case_suite "$case_id")"
  out_root="$(case_out_root "$case_id")"
  name="$(case_name "$case_id")"

  "$CASE_RUNNER" mark \
    --suite "$suite" \
    --out-root "$out_root" \
    --case-id "$case_id" \
    --name "$name" \
    --outcome "$outcome" \
    --reason "$reason" \
    --mods-dir "$mods_dir" \
    --command "manual client/server smoke session in $SESSION_ROOT" \
    --stdout "$stdout_payload" \
    --stderr "$stderr_payload"
}

copy_shared_case_files() {
  local case_id="$1"
  local target
  target="$(case_dir "$case_id")"
  mkdir -p "$target"

  for shared in "manual-checklist.md" "session-manifest.json" "observation-template.json"; do
    if [[ -f "$SESSION_ROOT/$shared" ]]; then
      cp -f "$SESSION_ROOT/$shared" "$target/$shared"
    fi
  done

  if [[ -f "$CLIENT_LAUNCH_KIT_DIR/launcher-jvm-args-generic.txt" ]]; then
    cp -f "$CLIENT_LAUNCH_KIT_DIR/launcher-jvm-args-generic.txt" "$target/client-jvm-args-generic.txt"
  fi
  if [[ -f "$CLIENT_LAUNCH_KIT_DIR/intermed-launch-kit.json" ]]; then
    cp -f "$CLIENT_LAUNCH_KIT_DIR/intermed-launch-kit.json" "$target/client-launch-kit.json"
  fi
  if [[ -f "$SESSION_ROOT/start-server.sh" ]]; then
    cp -f "$SESSION_ROOT/start-server.sh" "$target/start-server.sh"
  fi
  if [[ -f "$SESSION_ROOT/stop-server.sh" ]]; then
    cp -f "$SESSION_ROOT/stop-server.sh" "$target/stop-server.sh"
  fi
  if [[ -f "$CLIENT_GAME_DIR/logs/latest.log" ]]; then
    cp -f "$CLIENT_GAME_DIR/logs/latest.log" "$target/client-latest.log"
  fi
  if [[ -f "$SERVER_GAME_DIR/logs/latest.log" ]]; then
    cp -f "$SERVER_GAME_DIR/logs/latest.log" "$target/server-latest.log"
  fi
  if [[ -f "$SERVER_GAME_DIR/.intermed/vfs/diagnostics.json" ]]; then
    cp -f "$SERVER_GAME_DIR/.intermed/vfs/diagnostics.json" "$target/vfs-runtime-diagnostics.json"
  fi
}

resolve_server_base() {
  local explicit="${INTERMED_SERVER_BASE_FORGE:-}"
  local candidate=""
  local preferred=(
    "$ROOT_DIR/build/test-runs/$RUN_DATE/harness-forge-boot/raw/cache/server-base-forge"
    "$ROOT_DIR/build/test-runs/$RUN_DATE/nightly-corpus-real-small/CORPUS-001/harness-output/cache/server-base-forge"
  )

  if [[ -n "$explicit" && -d "$explicit" ]]; then
    printf '%s\n' "$explicit"
    return 0
  fi

  for candidate in "${preferred[@]}"; do
    if [[ -d "$candidate" ]]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done

  candidate="$(find "$ROOT_DIR/build/test-runs/$RUN_DATE" -type d -name 'server-base-forge' | sort | head -n 1)"
  if [[ -n "$candidate" && -d "$candidate" ]]; then
    printf '%s\n' "$candidate"
    return 0
  fi
  return 1
}

create_mixed_fixture_pack() {
  local fixtures="$SESSION_ROOT/_fixtures"
  if [[ -z "$JAR_BIN" || ! -x "$JAR_BIN" ]]; then
    echo "[client-server-smoke] jar tool not found; set INTERMED_JAR_BIN or JAVA_BIN to a JDK 21 installation" >&2
    return 1
  fi
  rm -rf "$fixtures" "$SHARED_MODS_DIR"
  mkdir -p \
    "$fixtures/fabric/resources" \
    "$fixtures/forge/resources/META-INF" \
    "$fixtures/neoforge/resources/META-INF" \
    "$fixtures/datapack-base/resources/META-INF" \
    "$fixtures/datapack-base/resources/data/intermed_smoke/recipes" \
    "$fixtures/datapack-base/resources/data/intermed_smoke/tags/items" \
    "$fixtures/datapack-base/resources/data/intermed_smoke/loot_tables/chests" \
    "$fixtures/datapack-overlay/resources" \
    "$fixtures/datapack-overlay/resources/data/intermed_smoke/recipes" \
    "$fixtures/datapack-overlay/resources/data/intermed_smoke/tags/items" \
    "$fixtures/datapack-overlay/resources/data/intermed_smoke/loot_tables/chests"

  cat > "$fixtures/fabric/resources/fabric.mod.json" <<'JSON'
{
  "schemaVersion": 1,
  "id": "smoke_client_fabric",
  "version": "1.0.0",
  "name": "InterMed Client Smoke Fabric Fixture",
  "environment": "*",
  "depends": {
    "minecraft": "1.20.1"
  }
}
JSON
  "$JAR_BIN" --create --file "$fixtures/smoke-client-fabric-1.0.0.jar" -C "$fixtures/fabric/resources" .

  cat > "$fixtures/forge/resources/META-INF/mods.toml" <<'TOML'
modLoader="javafml"
loaderVersion="[47,)"
license="MIT"

[[mods]]
modId="smoke_server_forge"
version="1.0.0"
displayName="InterMed Server Smoke Forge Fixture"
description="Minimal Forge metadata fixture for client/server smoke."
TOML
  "$JAR_BIN" --create --file "$fixtures/smoke-server-forge-1.0.0.jar" -C "$fixtures/forge/resources" .

  cat > "$fixtures/neoforge/resources/META-INF/neoforge.mods.toml" <<'TOML'
modLoader="javafml"
loaderVersion="[21.0,)"
license="MIT"

[[mods]]
modId="smoke_bridge_neoforge"
version="1.0.0"
displayName="InterMed Bridge Smoke NeoForge Fixture"
description="Minimal NeoForge metadata fixture for mixed-pack lifecycle smoke."
TOML
  "$JAR_BIN" --create --file "$fixtures/smoke-bridge-neoforge-1.0.0.jar" -C "$fixtures/neoforge/resources" .

  cat > "$fixtures/datapack-base/resources/META-INF/mods.toml" <<'TOML'
modLoader="javafml"
loaderVersion="[47,)"
license="MIT"

[[mods]]
modId="smoke_datapack_base"
version="1.0.0"
displayName="InterMed Datapack Base Fixture"
description="Base datapack fixture for recipe, tag, and loot reload smoke."
TOML
  cat > "$fixtures/datapack-base/resources/data/intermed_smoke/recipes/smoke_probe.json" <<'JSON'
{
  "type": "minecraft:crafting_shapeless",
  "ingredients": [
    {
      "item": "minecraft:stick"
    },
    {
      "item": "minecraft:coal"
    }
  ],
  "result": {
    "item": "minecraft:torch",
    "count": 4
  }
}
JSON
  cat > "$fixtures/datapack-base/resources/data/intermed_smoke/tags/items/smoke_probe.json" <<'JSON'
{
  "replace": false,
  "values": [
    "minecraft:stick"
  ]
}
JSON
  cat > "$fixtures/datapack-base/resources/data/intermed_smoke/loot_tables/chests/smoke_probe.json" <<'JSON'
{
  "type": "minecraft:chest",
  "pools": [
    {
      "rolls": 1,
      "entries": [
        {
          "type": "minecraft:item",
          "name": "minecraft:stick"
        }
      ]
    }
  ]
}
JSON
  "$JAR_BIN" --create --file "$fixtures/smoke-datapack-base-1.0.0.jar" -C "$fixtures/datapack-base/resources" .

  cat > "$fixtures/datapack-overlay/resources/fabric.mod.json" <<'JSON'
{
  "schemaVersion": 1,
  "id": "smoke_datapack_overlay",
  "version": "1.0.0",
  "name": "InterMed Datapack Overlay Fixture",
  "environment": "*",
  "depends": {
    "minecraft": "1.20.1"
  }
}
JSON
  cat > "$fixtures/datapack-overlay/resources/data/intermed_smoke/recipes/smoke_probe.json" <<'JSON'
{
  "type": "minecraft:crafting_shapeless",
  "ingredients": [
    {
      "item": "minecraft:stick"
    },
    {
      "item": "minecraft:coal"
    },
    {
      "item": "minecraft:string"
    }
  ],
  "result": {
    "item": "minecraft:torch",
    "count": 8
  }
}
JSON
  cat > "$fixtures/datapack-overlay/resources/data/intermed_smoke/tags/items/smoke_probe.json" <<'JSON'
{
  "replace": false,
  "values": [
    "minecraft:coal",
    "minecraft:string"
  ]
}
JSON
  cat > "$fixtures/datapack-overlay/resources/data/intermed_smoke/loot_tables/chests/smoke_probe.json" <<'JSON'
{
  "type": "minecraft:chest",
  "pools": [
    {
      "rolls": 1,
      "entries": [
        {
          "type": "minecraft:item",
          "name": "minecraft:coal"
        },
        {
          "type": "minecraft:item",
          "name": "minecraft:string"
        }
      ]
    }
  ]
}
JSON
  "$JAR_BIN" --create --file "$fixtures/smoke-datapack-overlay-1.0.0.jar" -C "$fixtures/datapack-overlay/resources" .

  mkdir -p "$SHARED_MODS_DIR"
  cp "$fixtures/"*.jar "$SHARED_MODS_DIR"/
}

stage_server_game() {
  local base_dir=""
  local args_file=""
  base_dir="$(resolve_server_base)"

  rm -rf "$SERVER_GAME_DIR"
  mkdir -p "$SERVER_GAME_DIR"
  cp -a "$base_dir"/. "$SERVER_GAME_DIR"/
  mkdir -p "$SERVER_GAME_DIR/intermed_mods" "$SERVER_GAME_DIR/config"
  cp "$SHARED_MODS_DIR/"*.jar "$SERVER_GAME_DIR/intermed_mods/"

  cat > "$SERVER_GAME_DIR/config/intermed-runtime.properties" <<'EOF'
aot.cache.enabled=true
mixin.conflict.policy=bridge
mixin.ast.reclaim.enabled=true
security.strict.mode=false
security.legacy.broad.permissions.enabled=true
vfs.enabled=true
vfs.conflict.policy=merge_then_priority
sandbox.espresso.enabled=false
sandbox.wasm.enabled=false
runtime.env=server
resolver.allow.fallback=true
EOF

  cat > "$SERVER_GAME_DIR/eula.txt" <<'EOF'
eula=true
EOF

  cat > "$SERVER_GAME_DIR/server.properties" <<EOF
online-mode=false
server-port=$SERVER_PORT
level-type=flat
generate-structures=false
spawn-monsters=false
view-distance=4
simulation-distance=4
motd=InterMed client-server smoke
EOF

  args_file="$(find "$SERVER_GAME_DIR/libraries" -type f -name 'unix_args.txt' | head -n 1)"
  if [[ -z "${args_file:-}" ]]; then
    echo "[client-server-smoke] missing unix_args.txt for forge base runtime" >&2
    return 1
  fi

  cat > "$SESSION_ROOT/start-server.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
cd "$SERVER_GAME_DIR"
exec "$JAVA_BIN" -Xmx1536m -Xms512m -XX:+UseZGC -XX:+ZGenerational -javaagent:"$CORE_JAR" --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -Druntime.game.dir="$SERVER_GAME_DIR" -Druntime.env=server -Dintermed.modsDir="$SERVER_GAME_DIR/intermed_mods" @"$args_file" nogui
EOF
  chmod +x "$SESSION_ROOT/start-server.sh"

  cat > "$SESSION_ROOT/stop-server.sh" <<EOF
#!/usr/bin/env bash
set -euo pipefail
pkill -f "$SERVER_GAME_DIR.*nogui" >/dev/null 2>&1 || true
EOF
  chmod +x "$SESSION_ROOT/stop-server.sh"
}

stage_client_game() {
  rm -rf "$CLIENT_GAME_DIR" "$CLIENT_LAUNCH_KIT_DIR"
  mkdir -p "$CLIENT_GAME_DIR/intermed_mods" "$CLIENT_GAME_DIR/config"
  cp "$SHARED_MODS_DIR/"*.jar "$CLIENT_GAME_DIR/intermed_mods/"

  cat > "$CLIENT_GAME_DIR/config/intermed-runtime.properties" <<'EOF'
aot.cache.enabled=true
mixin.conflict.policy=bridge
mixin.ast.reclaim.enabled=true
security.strict.mode=false
security.legacy.broad.permissions.enabled=true
vfs.enabled=true
vfs.conflict.policy=merge_then_priority
sandbox.espresso.enabled=false
sandbox.wasm.enabled=false
runtime.env=client
resolver.allow.fallback=true
EOF

  "$JAVA_BIN" -jar "$CORE_JAR" launch-kit \
    --agent "$CORE_JAR" \
    --fabric-agent "$FABRIC_JAR" \
    --game-dir "$CLIENT_GAME_DIR" \
    --mods-dir "$CLIENT_GAME_DIR/intermed_mods" \
    --output-dir "$CLIENT_LAUNCH_KIT_DIR" \
    > "$SESSION_ROOT/client-launch-kit.stdout.log" \
    2> "$SESSION_ROOT/client-launch-kit.stderr.log"
}

write_observation_template() {
  cat > "$OBS_TEMPLATE" <<EOF
{
  "session": {
    "observer": "",
    "started_at": "",
    "ended_at": "",
    "server_port": $SERVER_PORT,
    "server_started": false,
    "client_profile": "Forge 1.20.1-47.3.0",
    "client_game_dir": "$CLIENT_GAME_DIR",
    "server_game_dir": "$SERVER_GAME_DIR",
    "notes": ""
  },
  "cases": {
    "BOOT-005": {
      "outcome": "not-run",
      "reason": "",
      "notes": "",
      "main_menu_reached": false
    },
    "REG-004": {
      "outcome": "not-run",
      "reason": "",
      "notes": "",
      "registry_snapshot_seen": false,
      "registry_mismatch_observed": false
    },
    "NET-001": {
      "outcome": "not-run",
      "reason": "",
      "notes": "",
      "login_succeeded": false
    },
    "NET-002": {
      "outcome": "not-run",
      "reason": "",
      "notes": "",
      "registry_snapshot_seen": false,
      "registry_mismatch_observed": false
    },
    "NET-003": {
      "outcome": "not-run",
      "reason": "",
      "notes": "",
      "payload_roundtrip_confirmed": false
    },
    "NET-004": {
      "outcome": "not-run",
      "reason": "",
      "notes": "",
      "play_session_minutes": 0,
      "clean_disconnect": false
    },
    "NET-005": {
      "outcome": "not-run",
      "reason": "",
      "notes": "",
      "reconnect_succeeded": false
    },
    "VFS-002": {
      "outcome": "not-run",
      "reason": "",
      "notes": "",
      "reload_command_ran": false,
      "expected_resource_preserved": false
    },
    "VFS-003": {
      "outcome": "not-run",
      "reason": "",
      "notes": "",
      "reload_command_ran": false,
      "expected_resource_preserved": false
    },
    "VFS-004": {
      "outcome": "not-run",
      "reason": "",
      "notes": "",
      "reload_command_ran": false,
      "expected_resource_preserved": false
    }
  }
}
EOF
}

write_manual_checklist() {
  cat > "$CHECKLIST_PATH" <<EOF
# InterMed Client/Server Smoke Checklist

Prepared session root:
  $SESSION_ROOT

Prepared forge server:
  $SESSION_ROOT/start-server.sh

Prepared client game directory:
  $CLIENT_GAME_DIR

Prepared launch-kit JVM args:
  $CLIENT_LAUNCH_KIT_DIR/launcher-jvm-args-generic.txt

Recommended launcher path on this machine:
  /home/mak/Apps/TLauncher/run-tlauncher-java21.sh

Recommended client profile:
  Forge 1.20.1-47.3.0

Manual flow:
1. Run \`$SESSION_ROOT/start-server.sh\`.
2. In TLauncher create or edit a Forge 1.20.1-47.3.0 profile.
3. Set the profile game directory to \`$CLIENT_GAME_DIR\`.
4. Set Java to \`/home/mak/.local/jdks/temurin21-full/jdk-21.0.10+7/bin/java\`.
5. Paste the JVM args from \`$CLIENT_LAUNCH_KIT_DIR/launcher-jvm-args-generic.txt\`.
6. Launch the client and reach the main menu for \`BOOT-005\`.
7. Join \`localhost:$SERVER_PORT\` for \`REG-004\`, \`NET-001\`, and \`NET-002\`.
8. If join succeeds, stay connected for 3 to 5 minutes, interact, and disconnect cleanly for \`NET-004\`.
9. Reconnect once for \`NET-005\`.
10. While connected, run \`/reload\` once and confirm the session survives plus datapack-driven content still resolves for \`VFS-002\`, \`VFS-003\`, and \`VFS-004\`.
11. If you can verify a custom InterMed payload roundtrip through logs or gameplay instrumentation, record it for \`NET-003\`. If not, leave it as \`not-run\` or \`fail\` with notes.
12. Fill \`$OBS_TEMPLATE\` and rerun:
    \`INTERMED_RUN_CLIENT_SERVER_SMOKE=true INTERMED_CLIENT_SERVER_OBSERVATIONS=$OBS_TEMPLATE testing/run_client_server_smoke.sh\`

The prepared mixed pack includes:
- minimal Fabric, Forge, and NeoForge metadata fixtures
- base and overlay datapack fixtures for recipe, tag, and loot-table smoke
EOF
}

write_session_manifest() {
  python3 - "$SESSION_MANIFEST" "$RUN_DATE" "$SESSION_ROOT" "$CLIENT_GAME_DIR" "$SERVER_GAME_DIR" "$CLIENT_LAUNCH_KIT_DIR" "$OBS_TEMPLATE" "$SERVER_PORT" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

output = Path(sys.argv[1])
payload = {
    "schema": "intermed-client-server-smoke-session-v1",
    "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "date": sys.argv[2],
    "session_root": sys.argv[3],
    "client_game_dir": sys.argv[4],
    "server_game_dir": sys.argv[5],
    "client_launch_kit_dir": sys.argv[6],
    "observation_template": sys.argv[7],
    "server_port": int(sys.argv[8]),
    "cases": [
        "BOOT-005",
        "REG-004",
        "NET-001",
        "NET-002",
        "NET-003",
        "NET-004",
        "NET-005",
        "VFS-002",
        "VFS-003",
        "VFS-004",
    ],
}
output.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

prepare_session() {
  if [[ ! -f "$CORE_JAR" || ! -f "$FABRIC_JAR" || ! -f "$BOOTSTRAP_JAR" ]]; then
    if ! "$GRADLE_BIN" :app:coreJar :app:coreFabricJar :app:bootstrapJar --console=plain > "$SESSION_ROOT/setup-gradle.stdout.log" 2> "$SESSION_ROOT/setup-gradle.stderr.log"; then
      return 1
    fi
  else
    printf 'Reusing existing jars:\n%s\n%s\n%s\n' "$CORE_JAR" "$FABRIC_JAR" "$BOOTSTRAP_JAR" > "$SESSION_ROOT/setup-gradle.stdout.log"
    : > "$SESSION_ROOT/setup-gradle.stderr.log"
  fi

  create_mixed_fixture_pack || return 1
  stage_server_game || return 1
  stage_client_game || return 1
  write_observation_template
  write_manual_checklist
  write_session_manifest
}

write_observation_record() {
  local case_id="$1"
  local target_json="$2"
  python3 - "$OBS_FILE" "$case_id" "$target_json" <<'PY'
import json
import sys
from pathlib import Path

valid = {"pass", "fail", "unsupported", "not-run", "blocked"}
payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
case_id = sys.argv[2]
output = Path(sys.argv[3])
session = payload.get("session", {})
case = payload.get("cases", {}).get(case_id, {})
outcome = case.get("outcome", "not-run")
if outcome not in valid:
    outcome = "fail"
    reason = f"invalid observation outcome for {case_id}"
else:
    reason = case.get("reason") or case.get("notes") or "manual observation recorded"
record = {
    "case_id": case_id,
    "outcome": outcome,
    "reason": reason,
    "stdout": json.dumps({"session": session, "case": case}, indent=2, sort_keys=True),
    "stderr": "",
    "session": session,
    "case": case,
}
output.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

write_startup_report() {
  local target="$1"
  local case_json="$2"
  python3 - "$target" "$case_json" "$CLIENT_GAME_DIR" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

target = Path(sys.argv[1])
record = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
session = record.get("session", {})
case = record.get("case", {})
payload = {
    "schema": "intermed-client-smoke-startup-report-v1",
    "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "client_game_dir": sys.argv[3],
    "status": "main-menu" if case.get("main_menu_reached") else record.get("outcome"),
    "ready": bool(case.get("main_menu_reached")) and record.get("outcome") == "pass",
    "started_at": session.get("started_at", ""),
    "ended_at": session.get("ended_at", ""),
    "observer": session.get("observer", ""),
    "notes": case.get("notes", ""),
}
target.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

write_registry_report() {
  local target="$1"
  local case_json="$2"
  local case_id="$3"
  python3 - "$target" "$case_json" "$case_id" "$SERVER_GAME_DIR" "$CLIENT_GAME_DIR" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

target = Path(sys.argv[1])
record = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
session = record.get("session", {})
case = record.get("case", {})
payload = {
    "schema": "intermed-registry-smoke-report-v1",
    "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "case_id": sys.argv[3],
    "server_game_dir": sys.argv[4],
    "client_game_dir": sys.argv[5],
    "login_succeeded": bool(case.get("login_succeeded")) or record.get("outcome") == "pass",
    "registry_snapshot_seen": bool(case.get("registry_snapshot_seen")),
    "registry_mismatch_observed": bool(case.get("registry_mismatch_observed")),
    "observer": session.get("observer", ""),
    "notes": case.get("notes", ""),
}
target.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

write_vfs_report() {
  local target="$1"
  local case_json="$2"
  local case_id="$3"
  python3 - "$target" "$case_json" "$case_id" "$SERVER_GAME_DIR" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

target = Path(sys.argv[1])
record = json.loads(Path(sys.argv[2]).read_text(encoding="utf-8"))
session = record.get("session", {})
case = record.get("case", {})
payload = {
    "schema": "intermed-vfs-smoke-report-v1",
    "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "case_id": sys.argv[3],
    "server_game_dir": sys.argv[4],
    "reload_command_ran": bool(case.get("reload_command_ran")),
    "expected_resource_preserved": bool(case.get("expected_resource_preserved")),
    "observer": session.get("observer", ""),
    "notes": case.get("notes", ""),
}
target.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
}

finalize_case_from_observation() {
  local case_id="$1"
  local target
  local record_json
  local outcome
  local reason
  local stdout_payload
  local stderr_payload

  target="$(case_dir "$case_id")"
  record_json="$(mktemp)"
  write_observation_record "$case_id" "$record_json"

  outcome="$(python3 - "$record_json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(payload["outcome"])
PY
)"
  reason="$(python3 - "$record_json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(payload["reason"])
PY
)"
  stdout_payload="$(python3 - "$record_json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(payload["stdout"])
PY
)"
  stderr_payload="$(python3 - "$record_json" <<'PY'
import json
import sys
from pathlib import Path

payload = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
print(payload["stderr"])
PY
)"

  mark_case "$case_id" "$outcome" "$reason" "$stdout_payload" "$stderr_payload"
  copy_shared_case_files "$case_id"

  case "$case_id" in
    BOOT-005)
      write_startup_report "$target/startup-report.json" "$record_json"
      ;;
    REG-004|NET-002)
      write_registry_report "$target/registry-report.json" "$record_json" "$case_id"
      ;;
    VFS-002|VFS-003|VFS-004)
      write_vfs_report "$target/vfs-diagnostics.json" "$record_json" "$case_id"
      ;;
  esac

  rm -f "$record_json"
}

mark_pending_cases() {
  local reason="semi-automated client/server smoke session prepared at $SESSION_ROOT; run the checklist and then rerun with INTERMED_CLIENT_SERVER_OBSERVATIONS=$OBS_TEMPLATE"
  local stdout_payload=""
  stdout_payload="Prepared session root: $SESSION_ROOT"$'\n'
  stdout_payload+="Checklist: $CHECKLIST_PATH"$'\n'
  stdout_payload+="Observation template: $OBS_TEMPLATE"$'\n'
  stdout_payload+="Server start script: $SESSION_ROOT/start-server.sh"$'\n'
  stdout_payload+="Client JVM args: $CLIENT_LAUNCH_KIT_DIR/launcher-jvm-args-generic.txt"

  for case_id in "${all_cases[@]}"; do
    mark_case "$case_id" "not-run" "$reason" "$stdout_payload"
    copy_shared_case_files "$case_id"
  done
}

mark_all_safe_default() {
  local reason="safe default: set INTERMED_RUN_CLIENT_SERVER_SMOKE=true to prepare the shared client/server smoke session"
  local case_id=""
  for case_id in "${all_cases[@]}"; do
    mark_case "$case_id" "not-run" "$reason"
  done
}

mark_all_blocked() {
  local reason="$1"
  local case_id=""
  for case_id in "${all_cases[@]}"; do
    mark_case "$case_id" "blocked" "$reason"
  done
}

collect_all() {
  "$COLLECTOR" "$NIGHTLY_SUITE" "$NIGHTLY_OUT"
  "$COLLECTOR" "$WEEKLY_SUITE" "$WEEKLY_OUT"
}

if [[ "$RUN_CLIENT_SERVER" != "true" ]]; then
  mark_all_safe_default
  collect_all
  exit 0
fi

if ! prepare_session; then
  mark_all_blocked "client/server smoke preparation failed; inspect $SESSION_ROOT/setup-gradle.stderr.log"
  collect_all
  exit 1
fi

if [[ -n "$OBS_FILE" && -f "$OBS_FILE" ]]; then
  for case_id in "${all_cases[@]}"; do
    finalize_case_from_observation "$case_id"
  done
else
  mark_pending_cases
fi

collect_all
