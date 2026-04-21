#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

failures=0

section() {
  printf '\n== %s ==\n' "$1"
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  failures=$((failures + 1))
}

warn() {
  printf 'WARN: %s\n' "$1" >&2
}

section "Branch"
git branch --show-current

section "Dirty summary"
git status --short

section "Generated/local paths present"
generated_paths=(
  "app/logs"
  "harness-output"
  "metrics.jfr"
  "metrics.json"
)

found_generated=false
for path in "${generated_paths[@]}"; do
  if [ -e "$path" ]; then
    printf 'present: %s\n' "$path"
    found_generated=true
  fi
done

if [ "$found_generated" = false ]; then
  printf 'none\n'
fi

section "Generated/local files staged for commit"
staged_generated="$(
  git diff --cached --name-only -- \
    app/logs \
    harness-output \
    metrics.jfr \
    metrics.json \
    2>/dev/null || true
)"

if [ -n "$staged_generated" ]; then
  printf '%s\n' "$staged_generated"
  fail "generated runtime artifacts are staged; keep them out of the source snapshot"
else
  printf 'none\n'
fi

section "Root-level scratch candidates"
for path in .codex ModBootEvent.java RegistryFlushEvent.java; do
  if [ -e "$path" ]; then
    warn "$path exists at repository root; confirm before including it in the snapshot"
  fi
done

section "Release-source whitespace check"
release_paths=(
  ".gitignore"
  "README.md"
  "COMPLIANCE.md"
  "LAUNCH_CRITERIA.md"
  "ROADMAP.md"
  "docs"
  ".github"
  "scripts"
  "app/src"
  "mixin-fork"
  "test-harness"
  "settings.gradle.kts"
  "app/build.gradle.kts"
  "gradle.properties"
  "gradle"
  "gradlew"
  "gradlew.bat"
)

if ! git diff --check -- "${release_paths[@]}"; then
  fail "release-source unstaged diff check failed"
fi

if ! git diff --cached --check -- "${release_paths[@]}"; then
  fail "release-source staged diff check failed"
fi

section "Required evidence currently present"
evidence_paths=(
  "app/build/reports/tests"
  "app/build/test-results"
  "app/build/reports/microbench"
  "app/build/reports/security/hostile-smoke.txt"
  "app/build/reports/performance/alpha-performance-snapshot.json"
  "app/build/reports/performance/native-loader-baseline.json"
  "app/build/reports/performance/alpha-performance-smoke.jfr"
  "app/build/reports/soak"
  "app/build/reports/startup/warm-cache-startup.txt"
  "app/build/reports/observability/observability-evidence.txt"
  "app/build/reports/observability/intermed-metrics.json"
)

for path in "${evidence_paths[@]}"; do
  if [ -e "$path" ]; then
    printf 'present: %s\n' "$path"
  else
    printf 'missing: %s\n' "$path"
  fi
done

if [ "$failures" -gt 0 ]; then
  printf '\nalpha snapshot audit failed: %d blocking issue(s).\n' "$failures" >&2
  exit 1
fi

printf '\nalpha snapshot audit passed.\n'
