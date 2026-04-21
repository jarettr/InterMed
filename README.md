# InterMed

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

InterMed `v8.0.0-alpha.1` is an open-alpha Minecraft mod hypervisor preview targeting one frozen runtime scope:

- Minecraft `1.20.1`
- cross-loader compatibility foundations for `Fabric`, `Forge`, and `NeoForge`
- one shared runtime pipeline: discovery, dependency planning, DAG classloaders, bytecode remapping, security hooks, mixin analysis with AOT cache, and mod entrypoint boot

## Status

- Release status: `v8.0.0-alpha.1 / open alpha`
- Target Minecraft baseline: `1.20.1`
- Loader families under alpha scope: `Fabric`, `Forge`, `NeoForge`
- Frozen runtime claim: this alpha applies to `1.20.1` only. It must not be read as a compatibility claim for `1.20+` until real-world validation exists.
- Not claimed yet: production stability, broad real-mod compatibility proof, field-tested hostile-mod security, or field-tested performance targets

## Scope Contract

- [LAUNCH_CRITERIA.md](LAUNCH_CRITERIA.md) is the canonical public-alpha source of truth for frozen scope, mandatory release gates, and release artifacts. `README.md`, `COMPLIANCE.md`, and `ROADMAP.md` mirror that contract and must not broaden it.
- The accountable runtime path for this freeze is the canonical in-repo path:
  `InterMedKernel -> LifecycleManager -> LazyInterMedClassLoader`.
- Evidence levels are tracked in [COMPLIANCE.md](COMPLIANCE.md) as `Implemented`, `Wired`, `Synthetic-tested`, `Harness-tested`, `Field-tested`, or `Deferred`.
- Helper code, dormant modules, and forward-looking optimizations are not counted as launch-ready surfaces unless they are wired into that runtime path and have the required evidence level.
- External claims stay deferred: no beta/stable claim, no production-grade claim, no `95%` compatibility claim, and no `1.20+` compatibility claim in this freeze.

## Intentional Spec Deviations

- Runtime configuration is currently based on `intermed-runtime.properties` plus JSON override files. TOML/YAML is not the active configuration plane in this freeze.
- Runtime launch is currently based on a launcher-agnostic `launch-kit` generator, existing launcher profiles, and the thin CLI launcher. A dedicated GUI launcher is optional and deferred, not required for scope completion.

## Definition Of Done For This Freeze

- Docs and status matrices describe only the frozen `1.20.1` runtime scope.
- No document in the freeze claims support for `1.20+`, a mandatory GUI launcher, or TOML/YAML runtime config.
- Launch-ready surfaces are limited to what is actually wired into the canonical runtime path and listed with sufficient evidence in [LAUNCH_CRITERIA.md](LAUNCH_CRITERIA.md).

## Verification Lanes

- Baseline regression lane: `./gradlew :app:test`
- Machine-enforced coverage lane: `./gradlew :app:coverageGate`
- Strict fail-closed security lane: `./gradlew :app:strictSecurity`
- Runtime verification lane: `./gradlew :app:verifyRuntime`
- Harness self-test lane: `./gradlew :test-harness:test`
- Mandatory public-alpha app gate: `./gradlew :app:test :app:coverageGate :app:strictSecurity :app:verifyRuntime --rerun-tasks -Dintermed.allowRemoteForgeRepo=true --console=plain`
- Mandatory public-alpha release gate: the app gate above plus `./gradlew :test-harness:test --rerun-tasks --console=plain`, both from a clean checkout locally and in CI
- `:app:verifyRuntime` currently aggregates `compatibilitySmoke`, `registryMicrobench`, and `runtimeSoak`
- Alpha performance baseline lane: `./test-harness/run.sh performance-baseline --heap=768 --timeout=180`
- Evidence artifacts are emitted under `app/build/reports/{tests,jacoco,microbench,security,performance,soak,startup,observability}`; launch-failure triage uses `diagnostics-bundle` zip archives, which include a launch-readiness report, compatibility corpus manifest, compatibility sweep matrix, and machine-readable API gap matrix
- Compatibility-report runs and strict-security runs are intentionally separate; a permissive compatibility pass is not treated as proof of secure-by-default behavior
- Public alpha release assets include the versioned core runtime jar, fabric runtime jar, matching bootstrap support jar, harness jar, release checksums, MIT `LICENSE`, third-party notices, release notes/changelog, SBOM, and launcher-generated alpha evidence reports from the reproducible CI release job defined by [LAUNCH_CRITERIA.md](LAUNCH_CRITERIA.md)

## License

InterMed is released as open source under the [MIT License](LICENSE). Third-party dependency notes are tracked in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), and release builds publish a machine-readable SBOM.

## Community

Please read [CONTRIBUTING.md](CONTRIBUTING.md) before opening alpha issues or PRs. Project discussions follow [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md), and suspected vulnerabilities should be reported privately through GitHub private vulnerability reporting when available.

## Current Evidence Matrix

### Implemented foundation

- metadata discovery and normalization across Fabric / Forge / NeoForge manifests
- PubGrub-style dependency planning with virtual bridge substitution
- DAG classloader isolation with Welsh-Powell library clustering
- runtime remapping and reflection-string rewriting
- registry virtualization, mixin conflict analysis, and AOT cache pipeline
- capability hooks, launcher diagnostics, launch-readiness reports, automatic launch-failure diagnostics bundles, compatibility corpus manifests, compatibility sweep matrices, compatibility report generation, SBOM export, and a curated Fabric/Forge/NeoForge API gap matrix

### Preview / hardening required before external launch

- Espresso sandbox runtime path
- Wasm/WIT ecosystem integration beyond synthetic scenarios
- field-scale native-baseline performance proof against external ecosystems
- long-running field soak validation beyond in-repo runtime soak gates
- hostile/public-mod field security validation
- broad compatibility proof against external mod corpora

## Project Docs

- Compliance matrix: [COMPLIANCE.md](COMPLIANCE.md)
- Launch criteria: [LAUNCH_CRITERIA.md](LAUNCH_CRITERIA.md)
- Delivery roadmap: [ROADMAP.md](ROADMAP.md)
- Internal RC checklist: [docs/internal-rc-checklist.md](docs/internal-rc-checklist.md)
- Alpha source inventory: [docs/alpha-snapshot-inventory.md](docs/alpha-snapshot-inventory.md)
- Alpha sign-off: [docs/alpha-signoff-2026-04-19.md](docs/alpha-signoff-2026-04-19.md)
- Alpha runtime delta: [docs/alpha-runtime-delta-2026-04-20.md](docs/alpha-runtime-delta-2026-04-20.md)
- Alpha risk register: [docs/alpha-risk-register-2026-04-20.md](docs/alpha-risk-register-2026-04-20.md)
- Alpha security posture: [docs/alpha-security-posture-2026-04-21.md](docs/alpha-security-posture-2026-04-21.md)
- Alpha performance snapshot: [docs/alpha-performance-snapshot-2026-04-21.md](docs/alpha-performance-snapshot-2026-04-21.md)
- Alpha.1 release notes: [docs/release-notes-v8.0.0-alpha.1.md](docs/release-notes-v8.0.0-alpha.1.md)
- Alpha triage guide: [docs/alpha-triage.md](docs/alpha-triage.md)
- Known limitations: [docs/known-limitations.md](docs/known-limitations.md)
- Changelog: [CHANGELOG.md](CHANGELOG.md)

## Development

- Use Java 21 to run Gradle. If your system Java is newer, set `JAVA_HOME` or `INTERMED_JAVA_HOME`
  to a Java 21 installation before invoking `./gradlew`.
- The canonical runtime path is `InterMedKernel -> LifecycleManager -> LazyInterMedClassLoader`.
- Loader compatibility work in this freeze is shared across `Fabric`, `Forge`, and `NeoForge` on the `1.20.1` baseline.
- Public-alpha release posture stays `alpha` only; this freeze must not be described as `beta`, `stable`, or production-ready.
- Internal/public alpha sign-off commands for this freeze are `:app:test`, `:app:coverageGate`, `:app:strictSecurity`, `:app:verifyRuntime`, and `:test-harness:test`.
