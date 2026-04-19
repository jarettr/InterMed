# Alpha Snapshot Inventory

This document freezes the rules for turning the current dirty development tree
into a reviewable `v8.0-alpha-snapshot` source snapshot.

It is intentionally conservative: generated evidence can prove launch readiness,
but it should not be mixed into the source commit unless a maintainer explicitly
decides to publish a small, curated artifact in the repository.

## Snapshot Goal

- Snapshot name: `v8.0-alpha-snapshot`
- Branch target: `alpha/v8.0-snapshot-freeze`
- Commit target: one reviewable source commit after generated artifacts are
  removed from the index
- Runtime scope: Minecraft `1.20.1`, Fabric / Forge / NeoForge alpha bridge
  scope only
- Claim posture: internal RC hardening, not production-ready, not broad `1.20+`,
  not `95%` compatibility, not field-proven hostile-mod security

## Source Set Allowed In The Snapshot Commit

These paths are allowed to be part of the release-candidate source commit if
their diffs are intentional and pass the mandatory gates:

- `.github/workflows/**`
- `.github/ISSUE_TEMPLATE/**`
- `.github/SECURITY.md`
- `README.md`
- `COMPLIANCE.md`
- `ROADMAP.md`
- `LAUNCH_CRITERIA.md`
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

## Evidence Artifacts

These artifacts are useful for alpha sign-off, diagnostics, and release notes,
but they should normally be uploaded as CI/release artifacts instead of committed
as source:

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

If an evidence file must be preserved in Git, copy only a small redacted summary
into `docs/` and keep the raw runtime output outside the source tree.

## Paths Excluded From The Snapshot Commit

These paths are generated, local, too heavy, or unsafe to treat as source:

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

## Current Dirty-Tree Findings

The current tree is not yet a clean source snapshot. The audit found:

- `58` generated `app/logs/*.log.gz` files are already staged.
- `29` generated `app/logs/*.log.gz` files also have unstaged local changes.
- `harness-output/cache/**` contains downloaded Minecraft/mod artifacts and must
  stay outside the source commit.
- `harness-output/report/results.json` and `harness-output/report/index.html`
  are evidence, not source.
- `metrics.jfr` and `metrics.json` are local observability outputs and should not
  be committed as source.
- root-level `ModBootEvent.java` and `RegistryFlushEvent.java` exist outside
  `app/src/**`; confirm whether they are accidental scratch files before commit.
- `.codex` is an empty staged root file; confirm before commit.
- global `git diff --check` currently fails on
  `app/src/main/java/org/intermed/core/bridge/InterMedRegistry.java:64` because
  of trailing whitespace. That should be cleaned before the snapshot commit.

## Snapshot Audit Commands

Run the non-destructive audit first:

```bash
scripts/alpha_snapshot_audit.sh
```

Then run the mandatory runtime gates:

```bash
./gradlew :app:strictSecurity :app:test :app:verifyRuntime --no-daemon
```

Generate the machine-readable launch evidence after the gates pass:

```bash
./gradlew :app:run --args="launch-readiness-report \
  --output intermed-launch-readiness-report.json"

./gradlew :app:run --args="diagnostics-bundle \
  --output intermed-diagnostics-bundle.zip \
  --harness-results harness-output/report/results.json"
```

The generated JSON/zip files should be attached to release notes or CI artifacts
unless a maintainer explicitly chooses a small redacted source-controlled
summary.

## Cleanup Before The Snapshot Commit

Use non-destructive index cleanup. These commands do not delete files from disk;
they only remove generated/local files from the pending commit:

```bash
git restore --staged app/logs .codex
git restore --staged metrics.jfr metrics.json 2>/dev/null || true
```

If root scratch files are accidental, remove or move them only after owner
confirmation:

```bash
git status --short -- ModBootEvent.java RegistryFlushEvent.java
```

After cleanup, stage only the source set:

```bash
git add .gitignore README.md COMPLIANCE.md ROADMAP.md LAUNCH_CRITERIA.md docs .github
git add gradle gradle.properties gradlew gradlew.bat settings.gradle.kts
git add app/build.gradle.kts app/src mixin-fork test-harness scripts
```

Before committing, verify the staged snapshot:

```bash
git diff --cached --check
git diff --cached --stat
scripts/alpha_snapshot_audit.sh
./gradlew :app:strictSecurity :app:test :app:verifyRuntime --no-daemon
```

Suggested final commit:

```bash
git switch -c alpha/v8.0-snapshot-freeze
git commit -m "chore: freeze v8.0 alpha snapshot"
```

Do not create this commit while generated logs, harness caches, or unresolved
scratch files are staged.
