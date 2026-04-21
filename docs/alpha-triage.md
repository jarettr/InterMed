# InterMed v8.0 Alpha Triage Guide

This guide explains how to report issues during the `v8.0-alpha-snapshot`
hardening phase. The goal is reproducibility, not blame: a good report should
let a maintainer reproduce the same launch surface and classify the failure
without guessing.

## Before Filing

1. Confirm the test is inside the frozen scope:
   Minecraft `1.20.1`, Java 21, and Fabric/Forge/NeoForge alpha compatibility.
2. Read [known-limitations.md](known-limitations.md). If the behavior is already
   listed there, file only if you have new evidence, a smaller reproduction, or
   a regression from an earlier build.
3. Run the standard local gates when developing from source:
   `./gradlew :app:test`, `./gradlew :app:strictSecurity`, and
   `./gradlew :app:verifyRuntime`.
4. Generate or locate a diagnostics bundle.

## Diagnostics Bundle

`InterMedLauncher launch` automatically writes a diagnostics bundle when the
launched process exits with a non-zero code. By default it is written under:

```text
<gameDir>/.intermed/diagnostics/
```

For CI or deterministic repros, pin the output path:

```bash
java -cp InterMedCore.jar org.intermed.launcher.InterMedLauncher launch \
  --game-dir /path/to/game \
  --mods-dir /path/to/game/intermed_mods \
  --diagnostics-output /path/to/intermed-launch-failure.zip \
  --jar minecraft_server.jar nogui
```

If the launch path did not create a bundle, generate one manually:

```bash
java -cp InterMedCore.jar org.intermed.launcher.InterMedLauncher diagnostics-bundle \
  --game-dir /path/to/game \
  --mods-dir /path/to/game/intermed_mods \
  --harness-results /path/to/harness-output/report/results.json \
  --output /path/to/intermed-diagnostics-bundle.zip
```

The bundle should contain:

- `manifest.json`
- `reports/launch-readiness-report.json`
- `reports/compatibility-report.json`
- `reports/compatibility-corpus.json`
- `reports/compatibility-sweep-matrix.json`
- `reports/sbom.cdx.json`
- `reports/api-gap-matrix.json`
- `reports/dependency-plan.json`
- `reports/security-report.json`
- `reports/runtime-config.json`
- selected logs and runtime evidence reports when present
- `artifacts/harness/results.json` when `--harness-results` was provided

## What To File

Use the closest issue template:

- Crash or launch failure: process exits, freezes during startup, dependency
  resolution fails, Mixin application fails, or the game never reaches the main
  menu/server ready state.
- Compatibility gap: a specific public mod/API method/event works on its native
  loader but not through InterMed. Attach the diagnostics bundle when possible;
  `reports/compatibility-corpus.json` identifies the exact tested JAR hashes,
  `reports/compatibility-sweep-matrix.json` links those candidates to stored
  harness outcomes when available, and `reports/api-gap-matrix.json` helps
  maintainers distinguish a known missing bridge symbol from a regression in an
  implemented shim.
- Performance regression: startup, TPS, memory, GC, or hot-path timing is worse
  than a comparable native-loader baseline. Attach
  `app/build/reports/performance/alpha-performance-snapshot.json` when available,
  but do not treat internal microbench reports as native-loader overhead proof.
- Security hardening: strict-mode behavior, capability boundaries, sandbox
  routing, or denial diagnostics need attention. Attach
  `logs/intermed-security.log` and `app/build/reports/security/hostile-smoke.txt`
  if the report concerns the strict-security lane.

## Privacy Checklist

Before uploading a diagnostics bundle, check whether logs contain:

- access tokens, session IDs, usernames you consider private, or server IPs
- private mod JAR names or local filesystem paths
- API keys in mod config files

Redact sensitive values before posting publicly. For sensitive security issues,
follow `.github/SECURITY.md` instead of opening a public issue.

## Maintainer Triage Labels

Suggested first-pass classification:

- `area:classloading`
- `area:dependency-resolution`
- `area:mixin`
- `area:remapping`
- `area:registry`
- `area:vfs`
- `area:network`
- `area:security`
- `area:performance`
- `needs:diagnostics-bundle`
- `needs:field-repro`

## Minimal Reproduction Standard

A report is actionable when it includes:

- exact InterMed build or commit
- Java version and OS
- Minecraft version
- loader family or mixed-loader scenario
- mod list with versions
- whether strict security was enabled
- diagnostics bundle or a clear reason it cannot be shared
- expected behavior on native Forge/Fabric/NeoForge, if known
