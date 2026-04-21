# Alpha Risk Register - 2026-04-20

This register lists the highest-signal risks remaining after the
`2026-04-20` NeoForge runtime-shim cleanup, compatibility sweep matcher
hardening, and data/resource pack corpus-linkage pass. It is intentionally
conservative: items stay open until evidence exists, not until code merely
exists.

| ID | Risk | Current evidence | Impact | Next action |
| --- | --- | --- | --- | --- |
| `R-001` | Most corpus candidates are still not run by the linked harness matrix. | `compat-sweep-matrix` shows `183` unique linked candidates and `1635` not-run candidates from a corpus total of `1818`. | Blocks any public compatibility percentage and keeps the project below broad external alpha proof. | Expand harness coverage and publish unsupported/fail/pass accounting without presenting it as gameplay proof. |
| `R-001A` | Expanded local Phase 2 sweep can overwhelm a small desktop. | `single --top=500 --concurrency=4 --heap=2048` planned `596` server cases and was stopped after the machine entered severe memory pressure; VS Code was killed by the desktop OOM killer. | Running the alpha proof locally can corrupt the developer session and produce incomplete evidence. | Use the documented low-resource sharded plan: `--concurrency=1 --heap=768` with shard indexes, or run the full proof on a larger CI/runner. |
| `R-003` | Curated API gap matrix is complete, but API parity is still not proven. | `api-gap-matrix` is `92/92` present after adding minimal Fabric beta shims for `FabricBlockSettings`, `CommandRegistrationCallback`, and `UseBlockCallback`. | Removes the curated symbol gap, but does not prove full Fabric/Forge/NeoForge API behavior against public mods. | Keep the matrix complete, expand it with real external failure frequency, and do not describe it as full API parity. |
| `R-004` | Permissive harness success is not security proof. | Strict security exists as a separate lane; stored compatibility results are boot/startup evidence only. Synthetic hostile smoke now covers the minimum alpha cases, but public hostile-mod validation is still open. | A permissive pass can still fail under strict capabilities, hostile inputs, or real runtime behavior. | Keep `:app:strictSecurity` mandatory, publish `hostile-smoke.txt`, and never merge compatibility percentages into security claims. |
| `R-005` | GraalVM sandbox probe emits an initialization warning during diagnostics generation. | `diagnostics-bundle` succeeds, but logs `[GraalVMSandbox] initialize failed for mod '__probe__': ExceptionInInitializerError`. | Could mask a real Espresso runtime initialization problem before external sandbox validation. | Add a targeted probe test or downgrade/clarify the probe path if this is expected without GraalVM runtime components. |
| `R-006` | Real gameplay and multiplayer behavior remain unproven. | Network and registry bridges have synthetic/in-repo evidence; no real client-server handshake/play-session matrix is attached. | Blocks beta/stable launch language and any multiplayer compatibility claims. | Run mixed-loader client/server smoke packs with login, registry sync, payload exchange, and short play sessions. |
| `R-007` | Performance targets need field-scale native-loader baselines. | Microbench, soak, `alpha-performance-snapshot.json`, and the lightweight `native-loader-baseline.json` short-smoke lanes exist. | Still blocks the `10-15%` overhead claim for real modpacks because the alpha baseline is a short dedicated-server smoke, not a field-scale pack comparison. | Keep the lightweight baseline mandatory for alpha, then capture longer native Fabric/Forge/InterMed pack baselines on a suitable runner before publishing overhead percentages. |

## Current API Gap Snapshot

- Total tracked symbols: `92`
- Present: `92`
- Missing: `0`
- Alpha-stage symbols: `87/87` present
- Beta-stage symbols: `5/5` present
- NeoForge tracked surface: `17/17` present

## Current Sweep Snapshot

- Corpus total: `1818`
- Parsed candidates: `1774`
- Unsupported candidates: `40`
- Failed corpus parses: `4`
- Unique linked candidates: `183`
- Untested candidates: `1635`
- Harness results total: `184`
- Harness pass count: `184`
- Harness fail count: `0`
- Unmatched harness results: `0`
- Phase 2 expanded local single/pair/slice sweeps: `not-run` after the memory
  pressure stop; see `docs/alpha-compatibility-proof-2026-04-20.md`.
- Phase 3 `--mode=full` pack-style combos: `not-run` by request.

## Closed In This Pass

- `R-002`: The remaining unmatched harness result, `single-terralith-fabric`,
  is now linked through reporting-only discovery/parsing of `.zip`
  data/resource pack archives. Runtime classpath discovery remains JAR-only, so
  this closes the evidence-linkage gap without loading data packs as Java mods.

## Unmatched Harness Result IDs

- None.
