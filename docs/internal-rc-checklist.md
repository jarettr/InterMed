# InterMed Internal RC Checklist

This checklist defines what `internal RC` means for the frozen `v8.0-alpha-snapshot` scope.

## Scope Guardrails

- Minecraft scope stays frozen at `1.20.1`
- Loader scope stays frozen at `Fabric`, `Forge`, and `NeoForge`
- Snapshot source scope follows [alpha-snapshot-inventory.md](alpha-snapshot-inventory.md)
- No document claims `1.20+`, `95%` external compatibility, production stability, or field-proven hostile-mod security
- Active configuration remains `intermed-runtime.properties` plus JSON overrides
- Active launch remains JVM args, existing launcher profiles, and the thin CLI launcher

## Mandatory Gates

- `scripts/alpha_snapshot_audit.sh`
- `./gradlew :app:test :app:coverageGate :app:strictSecurity :app:verifyRuntime --rerun-tasks -Dintermed.allowRemoteForgeRepo=true --console=plain --stacktrace --no-daemon`
- `./gradlew :test-harness:test --rerun-tasks --console=plain --stacktrace --no-daemon`

## What Each Gate Means

- `:app:test`
  verifies the frozen runtime path, integration tests, and targeted regression coverage
- `:app:coverageGate`
  verifies the machine-enforced alpha coverage baseline and emits JaCoCo HTML/XML artifacts
- `:app:strictSecurity`
  verifies fail-closed security, sandbox, and runtime-config behavior under strict defaults
- `:app:verifyRuntime`
  verifies the aggregate runtime lane:
  `compatibilitySmoke`, `registryMicrobench`, and `runtimeSoak`
- `:test-harness:test`
  verifies the standalone compatibility harness surface used by public alpha evidence and release jobs

## Required Evidence Artifacts

- `app/build/reports/tests`
- `app/build/test-results`
- `app/build/reports/jacoco/test/html/index.html`
- `app/build/reports/jacoco/test/jacocoTestReport.xml`
- `app/build/reports/microbench`
- `app/build/reports/security/hostile-smoke.txt`
- `app/build/reports/performance/alpha-performance-snapshot.json`
- `app/build/reports/performance/native-loader-baseline.json`
- `app/build/reports/performance/alpha-performance-smoke.jfr`
- `app/build/reports/soak`
- `app/build/reports/startup/warm-cache-startup.txt`
- `app/build/reports/observability/observability-evidence.txt`
- `app/build/reports/observability/intermed-metrics.json`
- generated `intermed-launch-readiness-report.json` from the launcher `launch-readiness-report` command
- generated `intermed-compatibility-corpus.json` from the launcher `compat-corpus` command
- generated `intermed-compatibility-sweep-matrix.json` from the launcher `compat-sweep-matrix` command
- generated `intermed-api-gap-matrix.json` from the launcher `api-gap-matrix` command

## CI Expectations

- GitHub Actions `alpha-gate` job is green from a clean checkout
- GitHub Actions alpha release/evidence jobs use the same frozen-scope gate set without broadening claim language
- Uploaded artifacts include app + harness test results plus startup / observability / microbench / security / performance / soak reports

## Documentation Sync

- `README.md`, `COMPLIANCE.md`, `LAUNCH_CRITERIA.md`, and `ROADMAP.md` describe the same frozen launch posture
- `docs/compatibility-report.md` is clearly interpreted as a permissive compatibility lane, not a strict-security proof
- `docs/known-limitations.md`, `docs/alpha-triage.md`, and GitHub issue templates describe the same alpha support boundaries
- No module is described above its evidence level unless it is wired into the canonical runtime path and has matching proof

## Exit Criteria For Internal RC

- all mandatory gates pass locally
- all mandatory CI jobs pass
- startup warm-cache evidence is present and non-empty
- the launcher can generate a launch-readiness report without treating it as a replacement for mandatory gates
- the launcher can generate a compatibility corpus manifest for the current mod directory
- the launcher and diagnostics bundle can link a compatibility corpus to stored harness results without changing the evidence level to field-tested
- the launcher can generate an API gap matrix, and diagnostics bundles include it
- observability evidence includes both metrics output and a non-empty `.jfr` dump
- no known critical module remains outside the canonical runtime lifecycle
- release posture is still `v8.0-alpha-snapshot / internal RC hardening`, with public releases described as `alpha` only pending external field validation
