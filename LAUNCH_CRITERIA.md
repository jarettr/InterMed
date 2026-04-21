# InterMed v8.0 Launch Criteria

This document defines what must be true before InterMed moves from the current
`v8.0-alpha-snapshot` into wider external launch stages.

The goal is to keep launch language honest: a feature is not treated as launch
ready because code exists. It needs the evidence level required by the target
stage.

## Current Position

- Current version line: `v8.0-alpha-snapshot`
- Current release posture: `internal RC hardening / pre-launch alpha snapshot`
- Current Minecraft scope: `1.20.1`
- Current loader scope: `Fabric`, `Forge`, `NeoForge`
- Current public claim posture: `alpha` only; no `beta`, `stable`, or production-ready claim, no `95%` compatibility claim, no general `1.20+` compatibility claim, and no field-proven hostile-mod security claim

## Scope Authority

- This file is the canonical public-alpha source of truth for frozen scope, mandatory release gates, and release artifacts.
- `README.md`, `COMPLIANCE.md`, and `ROADMAP.md` must mirror this file and must not broaden it.

## Evidence Statuses

| Status | Meaning |
| --- | --- |
| `Implemented` | Code exists for the feature, but the feature may not be fully wired into the canonical runtime path. |
| `Wired` | The feature is connected to `InterMedKernel -> LifecycleManager -> LazyInterMedClassLoader` or an explicit runtime sidecar, but has only minimal behavioral proof. |
| `Synthetic-tested` | The feature is covered by unit/integration fixtures or synthetic test mods in the repository. |
| `Harness-tested` | The feature has been exercised by the compatibility harness against external mod artifacts under documented settings. |
| `Field-tested` | The feature has been validated in real client/server or modpack runs outside the narrow harness assumptions, with diagnostics collected. |
| `Deferred` | The feature is intentionally not part of the current launch claim. |

## Must Before Alpha

These items are required before any external alpha or technical preview.

| Area | Required state |
| --- | --- |
| Scope | README, compliance matrix, roadmap, user docs, and this file all describe `v8.0-alpha-snapshot`, Minecraft `1.20.1`, and no broad external guarantees. |
| Build gates | The combined alpha app gate `./gradlew :app:test :app:coverageGate :app:strictSecurity :app:verifyRuntime --rerun-tasks -Dintermed.allowRemoteForgeRepo=true --console=plain` plus `./gradlew :test-harness:test --rerun-tasks --console=plain` both pass from a clean checkout locally and in CI, so task dependencies cannot hide failures. |
| Coverage policy | Open-alpha coverage uses a staged gate, not the original 80% target: current machine enforcement is bundle line coverage `>=20%` plus selected launch/report/config classes `>=60%`. This must be documented as an alpha threshold. The next hardening step is package-level gates for `core`, `security`, `registry`, and `remapping` at `60-70%`, then a later move toward broad `80%` coverage before stable language. |
| Security posture | Strict security remains a separate fail-closed lane; permissive compatibility results are never presented as security proof. |
| Security alpha posture | Strict mode must be useful to testers: `CapabilityDeniedException` messages include mod/capability/scope/reason/action, example profiles exist under `examples/security-profiles/`, strict/permissive behavior is documented in `docs/user-guide.md`, and synthetic hostile smoke evidence is generated at `app/build/reports/security/hostile-smoke.txt`. |
| Compatibility language | Reports describe boot/startup evidence only unless real gameplay, client, server, or multiplayer behavior was actually tested. |
| Alpha compatibility proof | Before external alpha, publish `pass`/`fail`/`unsupported`/`not-run` accounting for expanded single, pair, and curated-slice boot lanes. Do not convert this into a compatibility percentage. The current proof-plan is `build/launch-evidence/intermed-alpha-compatibility-proof-plan.json`; the low-resource continuation plan is documented in `docs/alpha-compatibility-proof-2026-04-20.md`; large local sweeps that were not executed remain `not-run`. |
| API surface | Known Fabric/Forge/NeoForge API gaps are listed, not hidden behind generic loader-support wording. Current in-repo evidence includes a curated machine-readable `api-gap-matrix` report for alpha bridge symbols and known beta gaps. |
| Mixin evidence | Real Mixin compatibility claims stay below `Field-tested` until a public-mod corpus is exercised. |
| VFS evidence | VFS claims distinguish classpath/overlay resource routing from full Minecraft ResourceManager/DataPack lifecycle proof. |
| Network evidence | Network bridge claims distinguish synthetic packet tests from real client-server handshake/play proof. |
| Performance evidence | Microbench and soak reports are available, and the initial alpha performance snapshot is generated at `app/build/reports/performance/alpha-performance-snapshot.json` with a native-loader mirror at `app/build/reports/performance/native-loader-baseline.json`. Registry/remapper/event-bus microbench reports are internal hot-path evidence only, not real modpack overhead. No `10-15%` steady-state overhead claim is made from this short-smoke baseline. |
| Diagnostics | A failed external test can produce a `diagnostics-bundle` zip with logs, launch-readiness report, compatibility corpus manifest, compatibility sweep matrix, optional raw harness results, mod list, dependency plan, API gap matrix, security report, and relevant runtime reports. `InterMedLauncher launch` now writes this bundle automatically on non-zero process exit; real external crash uploads remain an alpha exercise. |
| Triage support | Public alpha reports have issue templates, a known-limitations page, and an alpha triage guide that require diagnostics or a clear reason diagnostics cannot be shared. |
| Release pipeline | Every public alpha release publishes the versioned `InterMedCore-*.jar`, `InterMedCore-*-fabric.jar`, the matching runtime bootstrap sidecar `InterMedCore-*-bootstrap.jar`, `intermed-test-harness-*.jar`, release checksums, SBOM, and attached launcher-generated `launch-readiness-report`, `api-gap-matrix`, `compat-corpus`, `compat-sweep-matrix`, and `alpha-performance-snapshot` artifacts from a reproducible CI release job. |

## Must Before Beta

These items are required before calling the project beta-quality.

| Area | Required state |
| --- | --- |
| Compatibility corpus | The current `compat-corpus` manifest and `compat-sweep-matrix` normalizer are populated from a top external mod corpus, linked to stored launch results, and published as a pass/fail/unsupported matrix. |
| API gap matrix | The current curated matrix is expanded with real external failure frequency, unsupported-symbol accounting, and stable prioritization rules. |
| Mixin corpus | Real-world Mixin configs/refmaps are categorized by feature and outcome. Unsupported features have explicit safe-fail behavior. |
| Client/server | Multiple mixed packs complete login, registry sync, custom payload exchange, and short play-session smoke tests. |
| Security hardening | Unknown mod-originated sensitive operations are blocked or explicitly diagnosed in strict mode. |
| Native/TCCL | Native library routing and thread context ClassLoader propagation are tested with real async/native fixtures. Current in-repo evidence covers native singleton routing/dedup/conflict diagnostics plus common wrapper/transformer async paths; hostile JNA/JNI and custom scheduler use still needs field-style fixtures. |
| VFS/DataPack | Recipes, tags, loot tables, and reload behavior are validated in a real Minecraft lifecycle. |
| Performance baseline | Native Forge/Fabric baselines exist for startup, TPS, p95/p99 tick time, heap, metaspace, and GC pauses. |
| Coverage | Coverage reporting exists, with package-level gates for core/security/registry/remapping raised to at least `60-70%`, or any remaining staged exception is explicitly justified with a dated removal plan. |

## Must Before Stable

These items are required before using stable or production-ready language.

| Area | Required state |
| --- | --- |
| Compatibility target | The project has measured evidence for any advertised compatibility percentage, including unsupported-mod accounting. |
| Version scope | Any `1.20+` claim is backed by separate validation per Minecraft minor version. |
| Security claim | Hostile-mod testing covers filesystem, network, reflection, process, native, Unsafe, VarHandle, FFM, async attribution, and sandbox escape attempts. |
| Long soak | Real packs survive long-running server/client soak tests with memory, TPS, JFR, and crash-bundle evidence. |
| Multiplayer | Client/server registry, packet, VFS, and remapping behavior is validated across representative mixed-loader packs. |
| Performance target | The `10-15%` overhead claim is backed by native-baseline comparisons, not only microbenchmarks. |
| User support | Crash bundles, issue templates, compatibility reports, known-limitation docs, and maintainer triage labels are exercised against real external alpha reports. |
| Governance | Release notes clearly separate fixed behavior, experimental behavior, known gaps, and unsupported scenarios. |

## Non-Claims Until Proven

- InterMed does not currently claim production stability.
- InterMed does not currently claim `95%` public-mod compatibility.
- InterMed does not currently claim general `Minecraft 1.20+` compatibility.
- InterMed does not currently claim field-proven hostile-mod security.
- InterMed does not currently claim that permissive compatibility harness passes are safe under strict security.
- InterMed does not currently claim full Forge, Fabric API, or NeoForge API parity.

## Alpha Exit Checklist

- `README.md`, `COMPLIANCE.md`, `ROADMAP.md`, `docs/user-guide.md`, and `docs/dev-guide.md` point readers to this file for launch status.
- `COMPLIANCE.md` uses the evidence statuses from this file instead of broad legacy labels.
- User-facing docs explain the frozen `1.20.1` alpha scope before installation steps.
- User-facing docs link to known limitations and alpha triage flow.
- Compatibility reports preserve their permissive-lane guardrails.
- Any field-tested claim has a dated report or issue link that can be audited later.
