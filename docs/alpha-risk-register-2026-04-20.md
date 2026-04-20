# Alpha Risk Register - 2026-04-20

This register lists the highest-signal risks remaining after the
`2026-04-20` NeoForge runtime-shim cleanup. It is intentionally conservative:
items stay open until evidence exists, not until code merely exists.

| ID | Risk | Current evidence | Impact | Next action |
| --- | --- | --- | --- | --- |
| `R-001` | Most corpus candidates are still not run by the linked harness matrix. | `compat-sweep-matrix` shows `141` linked candidates and `1663` not-run candidates from a corpus total of `1804`. | Blocks any public compatibility percentage and keeps the project below broad external alpha proof. | Expand harness coverage and publish unsupported/fail/pass accounting without presenting it as gameplay proof. |
| `R-002` | `11` passing harness results are not linked back to corpus candidates. | Unmatched results include Architectury/YACL Forge, C2ME Fabric, Connector Forge, Kotlin for Forge, owo-lib, Simple Voice Chat, Terralith, Timeless and Classics Zero, and Veinminer. | Reduces reproducibility of the evidence bundle and can hide duplicate/version/platform naming issues. | Normalize result-to-corpus matching across slug, loader, artifact classifier, and version aliases. |
| `R-003` | Fabric beta API surface still has curated gaps. | `api-gap-matrix` is `89/92` present; remaining missing symbols are `FabricBlockSettings`, `CommandRegistrationCallback`, and `UseBlockCallback`. | Safe for alpha if documented, but blocks beta-quality wording for the curated Fabric API surface. | Implement or explicitly defer these three beta shims with tests and bridge behavior notes. |
| `R-004` | Permissive harness success is not security proof. | Strict security exists as a separate lane; stored compatibility results are boot/startup evidence only. | A permissive pass can still fail under strict capabilities, hostile inputs, or real runtime behavior. | Keep `:app:strictSecurity` mandatory and never merge compatibility percentages into security claims. |
| `R-005` | GraalVM sandbox probe emits an initialization warning during diagnostics generation. | `diagnostics-bundle` succeeds, but logs `[GraalVMSandbox] initialize failed for mod '__probe__': ExceptionInInitializerError`. | Could mask a real Espresso runtime initialization problem before external sandbox validation. | Add a targeted probe test or downgrade/clarify the probe path if this is expected without GraalVM runtime components. |
| `R-006` | Real gameplay and multiplayer behavior remain unproven. | Network and registry bridges have synthetic/in-repo evidence; no real client-server handshake/play-session matrix is attached. | Blocks beta/stable launch language and any multiplayer compatibility claims. | Run mixed-loader client/server smoke packs with login, registry sync, payload exchange, and short play sessions. |
| `R-007` | Performance targets lack native-loader baselines. | Microbench and soak gates exist, but no native Forge/Fabric/NeoForge comparison is attached. | Blocks the `10-15%` overhead claim and startup-time claims for real modpacks. | Capture native baseline startup, tick, heap, metaspace, GC, and JFR data for representative packs. |

## Current API Gap Snapshot

- Total tracked symbols: `92`
- Present: `89`
- Missing: `3`
- Alpha-stage symbols: `87/87` present
- Beta-stage symbols: `2/5` present
- NeoForge tracked surface: `17/17` present

## Unmatched Harness Result IDs

- `pair-yacl-architectury-api-forge`
- `single-architectury-api-forge`
- `single-c2me-fabric-fabric`
- `single-connector-forge`
- `single-kotlin-for-forge-forge`
- `single-owo-lib-fabric`
- `single-simple-voice-chat-fabric`
- `single-terralith-fabric`
- `single-timeless-and-classics-zero-forge`
- `single-veinminer-fabric`
- `single-yacl-forge`
