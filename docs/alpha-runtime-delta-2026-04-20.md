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

The local `build/launch-evidence/intermed-api-gap-matrix.json` was regenerated
from the rebuilt core jar. Current summary:

- Total tracked symbols: `92`
- Present: `89`
- Missing: `3`
- Fabric: `56/59` present
- Forge: `16/16` present
- NeoForge: `17/17` present
- Alpha-stage symbols: `87/87` present
- Beta-stage symbols: `2/5` present

Remaining missing symbols are beta-stage Fabric API surface only:

- `net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings`
- `net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback`
- `net.fabricmc.fabric.api.event.player.UseBlockCallback`

The diagnostics bundle was regenerated and still contains `17` entries. During
bundle generation the existing GraalVM sandbox probe warning remained visible:

```text
[GraalVMSandbox] initialize failed for mod '__probe__': ExceptionInInitializerError
```

The command completed successfully, but the warning remains a runtime-hardening
risk and is tracked in [alpha-risk-register-2026-04-20.md](alpha-risk-register-2026-04-20.md).

## Evidence Checksums

```text
5437a86816b27edf7aaad89546fdd1bd76b5ea451e1a479df81d467665c3c3fe  build/launch-evidence/intermed-api-gap-matrix.json
d0cf2d8e876ebfa2f2e6cdf320e05725a10181ad667cdb24976fff590eb567f6  build/launch-evidence/intermed-diagnostics-bundle.zip
```

## Non-Claims

- This delta does not claim full NeoForge API parity.
- This delta does not claim Fabric beta API completion.
- This delta does not convert synthetic or harness evidence into field evidence.
- The `184/184` stored harness passes remain startup/boot evidence for the
  provided harness results only.
- Real gameplay, multiplayer, hostile-mod security, and native-baseline
  performance validation remain outside the current proof level.
