#!/usr/bin/env bash
# InterMed Test Harness — end-user launcher script
# Usage: ./run.sh [command] [flags]
# See: java -jar intermed-test-harness.jar help

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(dirname "$SCRIPT_DIR")"
HARNESS_JAR="${SCRIPT_DIR}/build/libs/intermed-test-harness.jar"
INTERMED_JAR="${INTERMED_JAR:-${SCRIPT_DIR}/../app/build/libs/InterMedCore.jar}"
INTERMED_FABRIC_JAR="${INTERMED_FABRIC_JAR:-${SCRIPT_DIR}/../app/build/libs/InterMedCore-fabric.jar}"
INTERMED_BOOTSTRAP_JAR="${INTERMED_BOOTSTRAP_JAR:-${SCRIPT_DIR}/../app/build/libs/InterMedCore-bootstrap.jar}"

resolve_single_jar() {
  local pattern_dir="$1"
  local pattern_name="$2"
  find "$pattern_dir" -maxdepth 1 -type f -name "$pattern_name" | sort | tail -n 1
}

resolve_intermed_core_jar() {
  local pattern_dir="$1"
  find "$pattern_dir" -maxdepth 1 -type f -name 'InterMedCore*.jar' ! -name '*-bootstrap.jar' | sort | tail -n 1
}

HARNESS_JAR_RESOLVED="$(resolve_single_jar "${SCRIPT_DIR}/build/libs" 'intermed-test-harness*.jar')"
INTERMED_JAR_RESOLVED="$(resolve_intermed_core_jar "${SCRIPT_DIR}/../app/build/libs")"
INTERMED_FABRIC_JAR_RESOLVED="$(resolve_single_jar "${SCRIPT_DIR}/../app/build/libs" 'InterMedCore*-fabric.jar')"
INTERMED_BOOTSTRAP_JAR_RESOLVED="$(resolve_single_jar "${SCRIPT_DIR}/../app/build/libs" 'InterMedCore*-bootstrap.jar')"

if [[ -n "${HARNESS_JAR_RESOLVED:-}" ]]; then
  HARNESS_JAR="$HARNESS_JAR_RESOLVED"
fi

if [[ -n "${INTERMED_JAR_RESOLVED:-}" ]]; then
  INTERMED_JAR="$INTERMED_JAR_RESOLVED"
fi

if [[ -n "${INTERMED_FABRIC_JAR_RESOLVED:-}" ]]; then
  INTERMED_FABRIC_JAR="$INTERMED_FABRIC_JAR_RESOLVED"
fi

if [[ -n "${INTERMED_BOOTSTRAP_JAR_RESOLVED:-}" ]]; then
  INTERMED_BOOTSTRAP_JAR="$INTERMED_BOOTSTRAP_JAR_RESOLVED"
fi

# ── Sanity checks ──────────────────────────────────────────────────────────────

if [[ ! -f "$HARNESS_JAR" ]]; then
  echo "[Harness] JAR not found, building :test-harness:harnessJar ..."
  (cd "$REPO_DIR" && ./gradlew :test-harness:harnessJar)
  HARNESS_JAR="$(resolve_single_jar "${SCRIPT_DIR}/build/libs" 'intermed-test-harness*.jar')"
fi

if [[ ! -f "$INTERMED_JAR" || ! -f "$INTERMED_FABRIC_JAR" || ! -f "$INTERMED_BOOTSTRAP_JAR" ]]; then
  echo "[Harness] InterMed runtime artifacts not found, building :app:coreJar, :app:coreFabricJar and :app:bootstrapJar ..."
  (cd "$REPO_DIR" && ./gradlew :app:coreJar :app:coreFabricJar :app:bootstrapJar)
  INTERMED_JAR="$(resolve_intermed_core_jar "${SCRIPT_DIR}/../app/build/libs")"
  INTERMED_FABRIC_JAR="$(resolve_single_jar "${SCRIPT_DIR}/../app/build/libs" 'InterMedCore*-fabric.jar')"
  INTERMED_BOOTSTRAP_JAR="$(resolve_single_jar "${SCRIPT_DIR}/../app/build/libs" 'InterMedCore*-bootstrap.jar')"
fi

if [[ ! -f "$HARNESS_JAR" ]]; then
  HARNESS_JAR="$(resolve_single_jar "${SCRIPT_DIR}/build/libs" 'intermed-test-harness*.jar')"
fi

if [[ ! -f "$INTERMED_JAR" ]]; then
  INTERMED_JAR="$(resolve_intermed_core_jar "${SCRIPT_DIR}/../app/build/libs")"
fi

if [[ ! -f "$INTERMED_FABRIC_JAR" ]]; then
  INTERMED_FABRIC_JAR="$(resolve_single_jar "${SCRIPT_DIR}/../app/build/libs" 'InterMedCore*-fabric.jar')"
fi

if [[ ! -f "$INTERMED_BOOTSTRAP_JAR" ]]; then
  INTERMED_BOOTSTRAP_JAR="$(resolve_single_jar "${SCRIPT_DIR}/../app/build/libs" 'InterMedCore*-bootstrap.jar')"
fi

if [[ -z "${HARNESS_JAR:-}" || ! -f "$HARNESS_JAR" ]]; then
  echo "[ERROR] Harness JAR still not found after build in: ${SCRIPT_DIR}/build/libs"
  exit 1
fi

if [[ -z "${INTERMED_JAR:-}" || ! -f "$INTERMED_JAR" ]]; then
  echo "[ERROR] InterMed core JAR still not found after build in: ${SCRIPT_DIR}/../app/build/libs"
  exit 1
fi

if [[ -z "${INTERMED_FABRIC_JAR:-}" || ! -f "$INTERMED_FABRIC_JAR" ]]; then
  echo "[ERROR] InterMed Fabric JAR still not found after build in: ${SCRIPT_DIR}/../app/build/libs"
  exit 1
fi

if [[ -z "${INTERMED_BOOTSTRAP_JAR:-}" || ! -f "$INTERMED_BOOTSTRAP_JAR" ]]; then
  echo "[ERROR] InterMed bootstrap JAR still not found after build in: ${SCRIPT_DIR}/../app/build/libs"
  exit 1
fi

# Auto-detect preferred Java 21 executable
JAVA_EXE="${INTERMED_JAVA:-}"

if [[ -z "$JAVA_EXE" && -n "${INTERMED_JAVA_HOME:-}" && -x "${INTERMED_JAVA_HOME}/bin/java" ]]; then
  JAVA_EXE="${INTERMED_JAVA_HOME}/bin/java"
fi

if [[ -z "$JAVA_EXE" && -x "/home/mak/.local/jdks/temurin21-full/jdk-21.0.10+7/bin/java" ]]; then
  JAVA_EXE="/home/mak/.local/jdks/temurin21-full/jdk-21.0.10+7/bin/java"
fi

if [[ -z "$JAVA_EXE" && -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
  JAVA_EXE="${JAVA_HOME}/bin/java"
fi

if [[ -z "$JAVA_EXE" && $(command -v java 2>/dev/null) ]]; then
  JAVA_EXE="$(command -v java)"
fi

if [[ -z "$JAVA_EXE" ]]; then
  echo "[ERROR] java not found. Install JDK 21+ or set INTERMED_JAVA / INTERMED_JAVA_HOME."
  exit 1
fi

JAVA_VERSION=$("$JAVA_EXE" -version 2>&1 | head -1 | grep -oP '(?<=version ")([0-9]+)')
if [[ "$JAVA_VERSION" -lt 21 ]]; then
  echo "[ERROR] Java $JAVA_VERSION detected at $JAVA_EXE; Java 21+ is required."
  exit 1
fi
if [[ "$JAVA_VERSION" -ne 21 ]]; then
  echo "[WARN] Using Java $JAVA_VERSION at $JAVA_EXE. InterMed test baseline is Java 21."
fi

# Default output directory
OUTPUT_DIR="${OUTPUT_DIR:-./harness-output}"

# ── Help ───────────────────────────────────────────────────────────────────────

if [[ "${1:-}" == "help" || "${1:-}" == "--help" || -z "${1:-}" ]]; then
  echo ""
  echo "InterMed Compatibility Test Harness"
  echo "====================================="
  echo ""
  echo "Environment variables:"
  echo "  INTERMED_JAR   Path to InterMedCore.jar  (default: ../app/build/libs/InterMedCore.jar)"
  echo "  OUTPUT_DIR     Output directory           (default: ./harness-output)"
  echo ""
  echo "Quick start (tests top 1000 mods, 4 parallel slots):"
  echo "  ./run.sh full --top=1000 --concurrency=4"
  echo ""
  echo "Run from:"
  echo "  repo root:  ./test-harness/run.sh ..."
  echo "  or inside test-harness/: ./run.sh ..."
  echo ""
  echo "Step-by-step:"
  echo "  ./run.sh bootstrap               # Download MC + Forge/Fabric (one-time, ~500 MB)"
  echo "  ./run.sh discover --top=1000     # Fetch mod list from Modrinth (~1000 JARs)"
  echo "  ./run.sh run --concurrency=8     # Run tests (several hours)"
  echo "  ./run.sh report                  # Generate HTML/JSON report"
  echo ""
  echo "Resuming an interrupted run:"
  echo "  ./run.sh run --skip-bootstrap --skip-discover --concurrency=8"
  echo ""
  echo "Test only Fabric mods, pairs mode, top 100:"
  echo "  ./run.sh full --loader=fabric --mode=pairs --top=100 --pairs-top=30"
  echo ""
  echo "All flags: ./run.sh help-full"
  echo ""
  exit 0
fi

if [[ "${1:-}" == "help-full" ]]; then
  exec "$JAVA_EXE" -jar "$HARNESS_JAR" help
fi

# ── Launch ─────────────────────────────────────────────────────────────────────

echo "[Harness] Java  : $JAVA_EXE"
echo "[Harness] JAR   : $HARNESS_JAR"
echo "[Harness] InterMed: $INTERMED_JAR"
echo "[Harness] Output: $OUTPUT_DIR"
echo ""

exec "$JAVA_EXE" \
  -Xmx512m \
  ${JVM_ARGS:-} \
  -jar "$HARNESS_JAR" \
  "$@" \
  --intermed-jar="$INTERMED_JAR" \
  --output="$OUTPUT_DIR" \
  --java="$JAVA_EXE"
