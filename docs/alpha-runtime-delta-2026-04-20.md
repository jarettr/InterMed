# Alpha Runtime Delta - 2026-04-20

This note records the post-sign-off runtime delta after
[alpha-signoff-2026-04-19.md](alpha-signoff-2026-04-19.md). It is not a
production release note and does not expand the frozen `v8.0-alpha-snapshot`
scope beyond Minecraft `1.20.1`.

## Scope

- Branch: `alpha/v8.0-snapshot-freeze`
- Release line: `v8.0-alpha-snapshot`
- Runtime scope: Minecraft `1.20.1`
- Loader scope: Fabric / Forge / NeoForge alpha bridge scope
- Claim posture: internal RC hardening only

## Changed

- Promoted the NeoForge alpha compatibility shims from `test` sources into the
  runtime source set so the packaged core jar exposes the same minimal
  reflective API surface that the bridge tests exercise.
- Added a minimal runtime `RegisterCapabilitiesEvent` shim for the curated
  NeoForge beta capability symbol tracked by `api-gap-matrix`.
- Expanded the NeoForge registry shim surface used by `NeoForgeEventBridge`,
  including blocks, items, entity types, sounds, enchantments, effects, potions,
  creative tabs, recipes, particles, block entities, features, and NeoForge
  fluid types.
- Removed duplicate NeoForge test-only shim classes so tests now compile against
  the packaged runtime shims.
- Hardened compatibility sweep matching so stored harness results can link to
  corpus candidates across Modrinth slugs, manifest IDs, artifact filename
  aliases, loader classifiers, multi-loader jars, and version aliases.
- Added minimal runtime shims for the remaining curated Fabric beta symbols:
  `CommandRegistrationCallback`, `FabricBlockSettings`, and `UseBlockCallback`.

## Updated Evidence

Targeted bridge/report regression passed:

```bash
./gradlew :app:test \
  --tests org.intermed.core.bridge.NeoForgeEventBridgeTest \
  --tests org.intermed.core.bridge.NeoForgeNetworkBridgeTest \
  --tests org.intermed.core.report.ApiGapMatrixGeneratorTest \
  --no-daemon
```

Core jar rebuild passed:

```bash
./gradlew :app:coreJar --no-daemon
```

Compatibility sweep matcher regression passed:

```bash
./gradlew :app:test \
  --tests org.intermed.core.report.CompatibilitySweepMatrixGeneratorTest \
  --no-daemon
```

The local `build/launch-evidence/intermed-api-gap-matrix.json` was regenerated
from the rebuilt core jar. Current summary:

- Total tracked symbols: `92`
- Present: `92`
- Missing: `0`
- Fabric: `59/59` present
- Forge: `16/16` present
- NeoForge: `17/17` present
- Alpha-stage symbols: `87/87` present
- Beta-stage symbols: `5/5` present

The curated API gap matrix now has no missing symbols. This only means the
tracked class-level/bridge-level surface is present; it is not a full API parity
claim for Fabric, Forge, or NeoForge.

The local `build/launch-evidence/intermed-compatibility-sweep-matrix.json` was
regenerated from the same corpus and stored harness results. Current summary:

- Corpus total: `1804`
- Linked candidates: `188`
- Untested candidates: `1616`
- Harness results total: `184`
- Harness pass count: `184`
- Harness fail count: `0`
- Unmatched harness results: `1`

The only remaining unmatched harness result is `single-terralith-fabric`; no
matching `terralith` candidate is present in the current corpus artifact.

The diagnostics bundle was regenerated and still contains `17` entries. During
bundle generation the existing GraalVM sandbox probe warning remained visible:

```text
[GraalVMSandbox] initialize failed for mod '__probe__': ExceptionInInitializerError
```

The command completed successfully, but the warning remains a runtime-hardening
risk and is tracked in [alpha-risk-register-2026-04-20.md](alpha-risk-register-2026-04-20.md).

## Evidence Checksums

```text
b1fb8b398bf47c683fae1864c133d01db3eb16d14833d18fb24e97e13f0c6232  build/launch-evidence/intermed-api-gap-matrix.json
0b9707fdfc0f93da04e26c1e20774c1329ec5bb36c60238f222ee87963a1e687  build/launch-evidence/intermed-compatibility-sweep-matrix.json
f065bd9b5d7db7be2c893e9b2d48a43eb66d06acba318c4b08172c29bdd40d25  build/launch-evidence/intermed-launch-readiness-report.json
55d91706b9b0a200d97d4e804cad19746d5d93cf3201adc9d4c12e4c6537d69a  build/launch-evidence/intermed-diagnostics-bundle.zip
```

## Non-Claims

- This delta does not claim full NeoForge API parity.
- This delta does not claim full Fabric beta API parity beyond the curated
  matrix symbols.
- This delta does not convert synthetic or harness evidence into field evidence.
- The `184/184` stored harness passes remain startup/boot evidence for the
  provided harness results only.
- Real gameplay, multiplayer, hostile-mod security, and native-baseline
  performance validation remain outside the current proof level.
