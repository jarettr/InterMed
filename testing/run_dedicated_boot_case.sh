#!/usr/bin/env bash
set -euo pipefail

loader=""
agent_jar=""
base_dir=""
case_dir=""
mods_source_dir=""
java_bin="${JAVA_BIN:-${INTERMED_JAVA:-java}}"
timeout_seconds="${INTERMED_BOOT_TIMEOUT_SECONDS:-180}"
heap_mb="${INTERMED_BOOT_HEAP_MB:-1536}"
write_dependency_plan="false"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --loader)
      loader="$2"
      shift 2
      ;;
    --agent-jar)
      agent_jar="$2"
      shift 2
      ;;
    --base-dir)
      base_dir="$2"
      shift 2
      ;;
    --case-dir)
      case_dir="$2"
      shift 2
      ;;
    --mods-source-dir)
      mods_source_dir="$2"
      shift 2
      ;;
    --java-bin)
      java_bin="$2"
      shift 2
      ;;
    --timeout-seconds)
      timeout_seconds="$2"
      shift 2
      ;;
    --heap-mb)
      heap_mb="$2"
      shift 2
      ;;
    --write-dependency-plan)
      write_dependency_plan="true"
      shift
      ;;
    *)
      echo "[boot-case] unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if [[ -z "$loader" || -z "$agent_jar" || -z "$base_dir" || -z "$case_dir" || -z "$mods_source_dir" ]]; then
  echo "[boot-case] missing required arguments" >&2
  exit 2
fi

if [[ ! -d "$base_dir" ]]; then
  echo "[boot-case] missing base server directory: $base_dir" >&2
  exit 2
fi

if [[ ! -f "$agent_jar" ]]; then
  echo "[boot-case] missing agent jar: $agent_jar" >&2
  exit 2
fi

agent_jar="$(cd "$(dirname "$agent_jar")" && pwd)/$(basename "$agent_jar")"
base_dir="$(cd "$base_dir" && pwd)"
case_dir="$(mkdir -p "$case_dir" && cd "$case_dir" && pwd)"
mods_source_dir="$(cd "$mods_source_dir" && pwd)"

run_dir="$case_dir/runtime"
stdout_path="$case_dir/server-stdout.raw.log"
stderr_path="$case_dir/server-stderr.raw.log"
compat_report_path="$case_dir/compatibility-report.json"
startup_report_path="$case_dir/startup-report.json"
dependency_plan_path="$case_dir/dependency-plan.json"

rm -rf "$run_dir"
mkdir -p "$run_dir" "$case_dir"
cp -a "$base_dir"/. "$run_dir"/

mods_run_dir="$run_dir/intermed_mods"
mkdir -p "$mods_run_dir"

mods_copied=0
while IFS= read -r -d '' jar_path; do
  cp -f "$jar_path" "$mods_run_dir"/
  mods_copied=1
done < <(find "$mods_source_dir" -maxdepth 1 -type f -name '*.jar' -print0 | sort -z)

if [[ "$mods_copied" -eq 0 ]]; then
  echo "[boot-case] no jars found in $mods_source_dir" >&2
  exit 2
fi

mkdir -p "$run_dir/config"
server_port="$((25000 + (RANDOM % 3000)))"
cat > "$run_dir/config/intermed-runtime.properties" <<'EOF'
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

cat > "$run_dir/eula.txt" <<'EOF'
eula=true
EOF

cat > "$run_dir/server.properties" <<EOF
online-mode=false
server-port=$server_port
level-type=flat
generate-structures=false
spawn-monsters=false
view-distance=2
simulation-distance=2
EOF

find_unix_args() {
  find "$run_dir/libraries" -type f -name 'unix_args.txt' | head -n 1
}

command=(
  "$java_bin"
  "-Xmx${heap_mb}m"
  "-Xms512m"
  "-XX:+UseZGC"
  "-XX:+ZGenerational"
  "-javaagent:$agent_jar"
  "--add-opens=java.base/java.lang=ALL-UNNAMED"
  "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
  "-Druntime.game.dir=$run_dir"
  "-Druntime.env=server"
  "-Dintermed.modsDir=$mods_run_dir"
)

case "$loader" in
  fabric)
    command+=("-jar" "$run_dir/fabric-server-launch.jar")
    ;;
  forge|neoforge)
    args_file="$(find_unix_args)"
    if [[ -z "${args_file:-}" ]]; then
      echo "[boot-case] missing unix_args.txt for $loader host runtime" >&2
      exit 2
    fi
    command+=("@$args_file")
    ;;
  *)
    echo "[boot-case] unsupported loader: $loader" >&2
    exit 2
    ;;
esac

command+=("nogui")

echo "[boot-case] launching ${loader} dedicated server from $run_dir"
printf '[boot-case] command:'
for token in "${command[@]}"; do
  printf ' %q' "$token"
done
printf '\n'

: > "$stdout_path"
: > "$stderr_path"

start_epoch="$(date +%s)"
(
  cd "$run_dir"
  "${command[@]}"
) >"$stdout_path" 2>"$stderr_path" &
server_pid=$!

status="timeout"
exit_code=-1
startup_ms=0

cleanup_server() {
  if kill -0 "$server_pid" 2>/dev/null; then
    kill "$server_pid" 2>/dev/null || true
    sleep 2
  fi
  if kill -0 "$server_pid" 2>/dev/null; then
    kill -9 "$server_pid" 2>/dev/null || true
  fi
  wait "$server_pid" 2>/dev/null || true
}

while true; do
  if grep -q 'Done (' "$stdout_path"; then
    status="ready"
    startup_ms="$(( ($(date +%s) - start_epoch) * 1000 ))"
    exit_code=0
    cleanup_server
    break
  fi

  if ! kill -0 "$server_pid" 2>/dev/null; then
    wait "$server_pid" || exit_code=$?
    status="exited"
    break
  fi

  if (( $(date +%s) - start_epoch >= timeout_seconds )); then
    status="timeout"
    cleanup_server
    break
  fi

  sleep 1
done

if "$java_bin" -jar "$agent_jar" compat-smoke \
  --agent "$agent_jar" \
  --game-dir "$run_dir" \
  --mods-dir "$mods_run_dir" \
  --report "$compat_report_path" >/dev/null 2>&1; then
  :
fi

python3 - "$startup_report_path" "$loader" "$status" "$startup_ms" "$exit_code" "$case_dir" "$stdout_path" "$stderr_path" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

path = Path(sys.argv[1])
payload = {
    "schema": "intermed-dedicated-boot-startup-report-v1",
    "loader": sys.argv[2],
    "status": sys.argv[3],
    "ready": sys.argv[3] == "ready",
    "startup_ms": int(sys.argv[4]),
    "exit_code": int(sys.argv[5]),
    "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "case_dir": sys.argv[6],
    "stdout_log": sys.argv[7],
    "stderr_log": sys.argv[8],
}
path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY

if [[ "$write_dependency_plan" == "true" ]]; then
  python3 - "$mods_run_dir" "$compat_report_path" "$dependency_plan_path" "$loader" <<'PY'
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

mods_dir = Path(sys.argv[1])
compat_path = Path(sys.argv[2])
output_path = Path(sys.argv[3])
loader = sys.argv[4]

compat = {}
if compat_path.exists():
    compat = json.loads(compat_path.read_text(encoding="utf-8"))

compat_by_file = {}
for item in compat.get("mods", []):
    compat_by_file[item.get("file")] = item

candidates = []
for jar in sorted(mods_dir.glob("*.jar")):
    entry = compat_by_file.get(jar.name, {})
    candidates.append({
        "file": jar.name,
        "status": entry.get("status", "parsed"),
        "id": entry.get("id", jar.stem),
        "platform": entry.get("platform", "unknown"),
        "version": entry.get("version", "unknown"),
        "dependencies": entry.get("dependencies", []),
    })

payload = {
    "schema": "intermed-minimal-dependency-plan-v1",
    "generated_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "host_loader": loader,
    "resolution": {
        "status": "resolved",
        "mode": "minimal-fixture-pack",
        "notes": "Fixture mods only declare metadata and no explicit dependencies, so the normalized plan is the fixture roster itself.",
    },
    "candidates": candidates,
}
output_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n", encoding="utf-8")
PY
fi

cat "$stdout_path"
if [[ -s "$stderr_path" ]]; then
  cat "$stderr_path" >&2
fi

if [[ "$status" == "ready" ]]; then
  exit 0
fi

echo "[boot-case] server status: $status" >&2
exit 1
