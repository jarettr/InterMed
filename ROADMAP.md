# InterMed v8.0 Roadmap

## Current Freeze

- [LAUNCH_CRITERIA.md](LAUNCH_CRITERIA.md) is the canonical public-alpha source of truth for frozen scope, mandatory release gates, and release artifacts. This roadmap mirrors that contract and must not broaden it.
- Version line: `v8.0.0-alpha.2`
- Minecraft baseline: `1.20.1`
- Loader support in scope: `Fabric`, `Forge`, `NeoForge`
- Current release posture: `open alpha` only for the frozen `1.20.1` runtime path until later validation justifies anything broader
- Current objective: external alpha validation for the frozen `1.20.1` runtime path before beta language or any scope expansion

## Active Delivery Track

### Phase 0. Scope Freeze

- freeze one Minecraft version and one support matrix
- keep README, build metadata, and compliance docs aligned
- explicitly separate `Implemented`, `Wired`, `Synthetic-tested`, `Harness-tested`, `Field-tested`, and `Deferred` surfaces
- do not claim `1.20+` support until real-world validation exists
- treat `intermed-runtime.properties` plus JSON overrides as the active config plane for this freeze
- treat JVM args / existing launcher profiles / thin CLI launch as the active launch model for this freeze
- definition of done for Phase 0: no freeze document claims a surface above its current evidence status in the `1.20.1` runtime path

### Phase 1. Base Stabilization

- keep `:app:test` green
- keep launcher diagnostics stable
- do not allow skipped or disabled mandatory sandbox scenarios

### Phase 2. Verification Hardening

- keep `:app:strictSecurity` green as a fail-closed lane distinct from compatibility sweeps
- keep `:app:verifyRuntime` green as the aggregate runtime lane
- keep `compatibilitySmoke`, `registryMicrobench`, and `runtimeSoak` wired into build + CI
- emit reproducible artifacts for startup, observability, microbench, and soak evidence

### Phase 2.5. Internal RC

- keep README, compliance matrix, launch criteria, roadmap, and checklist in sync with the real gates
- require `:app:test`, `:app:coverageGate`, `:app:strictSecurity`, `:app:verifyRuntime`, and `:test-harness:test` before calling the freeze internally code-complete or publishing a public alpha build
- track only frozen-scope claims for `1.20.1`, `Fabric`, `Forge`, and `NeoForge`
- keep public release language at `alpha` only; no `beta`, `stable`, or production-ready wording for this freeze
- publish versioned core/fabric/harness jars, the matching bootstrap support jar, checksums, SBOM, and launcher-generated alpha evidence reports from a reproducible CI release job
- hold external compatibility / performance / hostile-mod claims until field validation exists and is recorded in `LAUNCH_CRITERIA.md`

### Phase 3. External Validation

- only after functional hardening
- real mod corpus / modpack validation
- compatibility dashboards, regression corpora, and field performance checks
