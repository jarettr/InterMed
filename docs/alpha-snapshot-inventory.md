# Alpha Source Inventory

This document records the source hygiene rules for publishing
`v8.0.0-alpha.1` as an open alpha. It replaces the earlier internal RC
snapshot notes with the decisions that should hold for the release commit.

## Release Goal

- Release line: `v8.0.0-alpha.1`
- Git tag target: `v8.0.0-alpha.1`
- Suggested release branch: `release/v8.0.0-alpha.1`
- Runtime scope: Minecraft `1.20.1`, Fabric / Forge / NeoForge alpha bridge
  scope only
- Claim posture: open alpha only; not production-ready, not broad `1.20+`, not
  `95%` compatibility, and not field-proven hostile-mod security

## Source Set Allowed In The Release Commit

These paths are allowed in the source commit when their diffs are intentional
and the mandatory gates pass:

- `.github/workflows/**`
- `.github/ISSUE_TEMPLATE/**`
- `.github/SECURITY.md`
- `README.md`
- `CHANGELOG.md`
- `COMPLIANCE.md`
- `ROADMAP.md`
- `LAUNCH_CRITERIA.md`
- `LICENSE`
- `THIRD_PARTY_NOTICES.md`
- `.gitignore`
- `docs/**`
- `gradle/**`
- `gradle.properties`
- `gradlew`
- `gradlew.bat`
- `settings.gradle.kts`
- `app/build.gradle.kts`
- `app/src/main/**`
- `app/src/test/**`
- `app/src/test/resources/**`
- `app/src/main/resources/**`
- `mixin-fork/**`
- `test-harness/src/**`
- `test-harness/build.gradle.kts`
- `scripts/*.sh` when the script is source-controlled tooling, not local output

## Explicit Cleanup Decisions

- Restore `gradlew.bat`: Windows users should be able to build from a clean
  checkout without installing Gradle manually.
- Remove tracked `app/bin/**`: these files are generated class/resource output
  and are already ignored by the repository rules.
- Remove root-level legacy test files under `app/*.java`: maintained tests live
  under `app/src/test/**`.
- Remove local Windows cleanup/guard scripts from the release source set:
  `InterMed_Cleanup.bat` and `InterMed_Guard.bat` contained maintainer-local
  paths and were not portable release tooling.
- Keep generated evidence out of Git. Release evidence belongs in CI/release
  artifacts unless a maintainer writes a small curated summary under `docs/`.

## Evidence Artifacts

These artifacts are useful for alpha sign-off, diagnostics, and release notes,
but should normally be uploaded by CI rather than committed as source:

- `app/build/reports/tests`
- `app/build/test-results`
- `app/build/reports/microbench`
- `app/build/reports/soak`
- `app/build/reports/startup/warm-cache-startup.txt`
- `app/build/reports/observability/observability-evidence.txt`
- `app/build/reports/observability/intermed-metrics.json`
- generated `intermed-launch-readiness-report.json`
- generated `intermed-compatibility-corpus.json`
- generated `intermed-compatibility-sweep-matrix.json`
- generated `intermed-api-gap-matrix.json`
- generated `diagnostics-bundle` zip files
- `harness-output/report/results.json` and `harness-output/report/index.html`,
  if they are tied to a dated compatibility run

## Paths Excluded From The Release Commit

These paths are generated, local, too heavy, or unsafe to treat as source:

- `app/bin/**`
- `app/logs/**`
- `harness-output/cache/**`
- `harness-output/runs/**`
- `harness-output/**` unless a maintainer intentionally promotes a tiny summary
- `metrics.jfr`
- `metrics.json`
- `.gradle/**`
- `build/**`
- `app/build/**`
- `mixin-fork/build/**`
- `test-harness/build/**`
- `*.hprof`
- `*.jfr`
- `*.log`
- `*.log.gz`

## Verification Commands

Before committing or tagging the alpha source release, verify:

```bash
git diff --check
./gradlew :app:test :app:coverageGate :app:strictSecurity :app:verifyRuntime --rerun-tasks -Dintermed.allowRemoteForgeRepo=true --console=plain
./gradlew :test-harness:test --rerun-tasks --console=plain
./gradlew :app:coreJar :app:coreFabricJar :app:bootstrapJar :test-harness:harnessJar -Dintermed.allowRemoteForgeRepo=true --console=plain
```

Then generate or inspect the release payload from the CI workflow. The public
payload must include:

- `InterMedCore-8.0.0-alpha.1.jar`
- `InterMedCore-8.0.0-alpha.1-fabric.jar`
- `InterMedCore-8.0.0-alpha.1-bootstrap.jar`
- `intermed-test-harness-8.0.0-alpha.1.jar`
- `SHA256SUMS.txt`
- `intermed-sbom.cdx.json`
- `intermed-launch-readiness-report.json`
- `intermed-api-gap-matrix.json`
- `intermed-compatibility-corpus.json`
- `intermed-compatibility-sweep-matrix.json`
- `alpha-performance-snapshot.json`
- `LICENSE`
- `THIRD_PARTY_NOTICES.md`
- `CHANGELOG.md`
- `release-notes-v8.0.0-alpha.1.md`

Suggested local source commit:

```bash
git commit -m "Prepare v8.0.0-alpha.1 open alpha release"
```

Suggested release tag after the source commit is reviewed:

```bash
git tag v8.0.0-alpha.1
```
