# InterMed v8.0.0-alpha.1 Open Alpha Compliance Matrix

This document tracks the frozen `v8.0.0-alpha.1` scope for the current codebase.

## Scope Freeze

- Minecraft version: `1.20.1`
- Loader families in scope: `Fabric`, `Forge`, `NeoForge`
- Release status: `v8.0.0-alpha.1 / open alpha`
- Field validation with real modpacks: deferred until after full functional hardening

## Scope Interpretation

- This matrix applies only to the frozen `1.20.1` runtime scope.
- [LAUNCH_CRITERIA.md](LAUNCH_CRITERIA.md) is the canonical public-alpha source of truth for frozen scope, mandatory release gates, and release artifacts. This matrix mirrors that contract and must not broaden it.
- Evidence status terms are shared with [LAUNCH_CRITERIA.md](LAUNCH_CRITERIA.md): `Implemented`, `Wired`, `Synthetic-tested`, `Harness-tested`, `Field-tested`, and `Deferred`.
- The canonical runtime path for support accounting is:
  `InterMedKernel -> LifecycleManager -> LazyInterMedClassLoader`.
- Standalone helpers, dormant optimizations, and future-facing code paths do not count toward launch readiness unless they are part of that path.
- No entry in this matrix should be read as a claim for `Minecraft 1.20+` until field validation exists.

## Intentional Deviations From The Original Spec

- The active configuration plane in this freeze is `intermed-runtime.properties` plus JSON override files. TOML/YAML runtime config is deferred.
- The active launch model in this freeze is JVM args, existing launcher profiles, and the thin CLI launcher. A mandatory GUI launcher is deferred.

## Definition Of Done For Scope Freeze

- README, roadmap, and compliance docs describe the same frozen runtime scope.
- The docs do not claim `1.20+`, mandatory GUI-launcher support, or TOML/YAML runtime config support.
- No requirement receives a launch-ready evidence status purely because code exists; it must be part of the runtime path used in this freeze and must have the required evidence for the target launch stage.

## Verification Lanes

- `:app:test` — baseline unit/integration regression lane for the frozen runtime path
- `:app:coverageGate` — machine-enforced alpha coverage lane
- `:app:strictSecurity` — strict fail-closed security/sandbox/config regression lane
- `:app:verifyRuntime` — aggregate runtime lane for `compatibilitySmoke`, `registryMicrobench`, and `runtimeSoak`
- `:test-harness:test` — harness self-test lane for the standalone compatibility runner
- Mandatory public-alpha app gate: `./gradlew :app:test :app:coverageGate :app:strictSecurity :app:verifyRuntime --rerun-tasks -Dintermed.allowRemoteForgeRepo=true --console=plain`
- Mandatory public-alpha release gate: the app gate above plus `./gradlew :test-harness:test --rerun-tasks --console=plain`, both from a clean checkout locally and in CI
- Evidence artifacts emitted in-repo: `app/build/reports/tests`, `microbench`, `soak`, `startup`, `observability`, CLI-generated `launch-readiness-report` / `compat-corpus` / `compat-sweep-matrix` / `api-gap-matrix` JSON, and `diagnostics-bundle` zip archives
- Compatibility sweeps remain distinct from strict-security proof. A permissive compatibility report is not treated as a secure-by-default pass.

## Evidence Status Legend

- `Implemented` — code exists for the feature, but it may not be fully wired into the canonical runtime path.
- `Wired` — connected to the canonical runtime path or an explicit runtime sidecar, with only minimal behavioral proof.
- `Synthetic-tested` — covered by unit/integration fixtures or synthetic test mods in the repository.
- `Harness-tested` — exercised by the compatibility harness against external mod artifacts under documented settings.
- `Field-tested` — validated in real client/server or modpack runs outside the narrow harness assumptions, with diagnostics collected.
- `Deferred` — intentionally not part of the current launch claim.

## Requirements Matrix

| Requirement | Evidence status | Code | Tests / evidence | Remaining gap |
| --- | --- | --- | --- | --- |
| 1. ClassLoader DAG + Welsh-Powell clustering | Harness-tested | `LifecycleManager`, `LazyInterMedClassLoader`, `WeakPeerPolicy`, `WelshPowellClusterer` | `LifecycleManagerIntegrationTest`, classloader tests, metadata tests, permissive compatibility harness boot evidence | Dynamic weak edges for soft dependencies are wired in-repo; broader external compatibility proof is still deferred |
| 2. PubGrub-style dependency resolution + virtual deps | Harness-tested | `LifecycleManager.DependencyResolver`, `PubGrubResolver`, `ResolvedPlan`, `RuntimeModuleKind` | `PubGrubResolverTest`, `SemVerConstraintTest`, `LibraryDiscoveryTest`, harness metadata/dependency planning | Ecosystem-scale validation is still deferred to later field testing |
| 3. Registry virtualization + shard-safe lookup path | Synthetic-tested | `RegistryCompatibilityContract`, `RegistryHookTransformer`, `RegistryLinker`, `VirtualRegistry*`, `InterMedAgent` | registry integration tests in `LifecycleManagerIntegrationTest`, `RegistryAdviceTest`, `RegistryLinkerTest`, `RegistryCompatibilityContractTest`, `VirtualRegistryServiceTest`, `RegistryHotPathMicrobenchTest` | External conflict-heavy modpack proof and real multiplayer registry synchronization are still deferred |
| 4. Mixin transmogrification | Synthetic-tested | `MixinTransmogrifier`, `InterMedMixinBootstrap`, platform agent, reflective runtime pinning | `MixinIntegrationTest`, mixin bootstrap tests | Broader field validation against public mixin stacks is still deferred |
| 5. AST conflict resolution + AOT cache | Synthetic-tested | `MixinASTAnalyzer`, `ResolutionEngine`, `SemanticContractRegistry`, `AOTCacheManager` | `MixinIntegrationTest`, AST tests, cache tests, `WarmCacheStartupEvidenceTest` | Semantic contracts cover key zones in synthetic/in-repo evidence; external modpack corpus proof still deferred |
| 6. Dynamic remapping in classloader pipeline | Synthetic-tested | `TinyRemapperTransformer`, `InterMedRemapper` | `InterMedRemapperTest`, `RemapperHotPathMicrobenchTest` | Hot-path budget proof is in-repo; ecosystem-scale profiling is still deferred |
| 7. Reflection remapping / dynamic string analysis | Synthetic-tested | `ReflectionTransformer`, remapper runtime translator | `InterMedRemapperTest` | Ambiguous runtime constructions still degrade to risky-mod warnings |
| 8. Capability-based security hooks | Synthetic-tested | `InterMedAgent`, `CapabilityManager`, `SecurityHookTransformer`, `SecurityPolicy`, `TcclInterceptor`, `NativeLinkerNode` | security integration tests in `LifecycleManagerIntegrationTest`, dedicated security tests, transformer coverage tests, async attribution tests, native routing/dedup tests, `strictSecurity` lane | Modern low-level JVM surfaces, native load routing, and common async attribution paths are intercepted in-repo; hostile public-mod field proof is still deferred |
| 9. Polyglot sandboxes (Espresso + Wasm) | Synthetic-tested | `PolyglotSandboxManager`, `GraalVMSandbox`, `WasmSandbox`, `WitContractCatalog` | `PolyglotSandboxManagerTest`, `GraalVMSandboxTest`, sandbox tests, `RuntimeStabilitySoakTest`, `strictSecurity` lane | Full Espresso/Wasm external runtime validation is still deferred |
| 10. GC/perf hot-path optimizations | Synthetic-tested | `EventFlyweightPool`, `LockFreeEvent`, registry fast-path plumbing, remapper hot-path cache | pool tests, registry tests, microbench budget gates, `verifyRuntime` lane | Full ecosystem-scale performance proof is still deferred, but in-repo budgets are enforced |
| 11. Adaptive monitoring, JFR, EWMA/CUSUM, throttling | Synthetic-tested | `ObservabilityMonitor`, `PrometheusExporter`, `OtelJsonExporter` | observability/throttling tests, `ObservabilityEvidenceTest` | In-repo metrics and JFR dump evidence are machine-checked; real workload validation is still deferred |

## Non-Functional Requirements

| Area | Status | Current position |
| --- | --- | --- |
| Performance overhead 10-15% | Deferred | Formal hot-path budget gates are enforced in `RegistryHotPathMicrobenchTest`, `LockFreeEventHotPathMicrobenchTest`, and `RemapperHotPathMicrobenchTest`, but native-baseline comparison against external ecosystems is still deferred |
| Startup time within 1.5x after warm cache | Synthetic-tested | `WarmCacheStartupEvidenceTest` machine-checks an end-to-end cold/warm startup path with AOT cache hits; native baseline comparison and field-scale startup proof are still deferred |
| 95% mod compatibility | Deferred | Explicitly deferred to later field testing |
| Security guarantee against arbitrary hostile mods | Deferred | Low-level memory/native interop surfaces and risky sandbox fallback are hardened in-repo, and `:app:strictSecurity` enforces a separate fail-closed lane; absolute guarantees require hostile real-world validation |
| Java 21 / Gradle / ASM / ByteBuddy / GraalVM / Chicory stack | Implemented | Present in the current codebase |

## Delivery Notes

- `Fabric`, `Forge`, and `NeoForge` are all inside the frozen alpha scope for `1.20.1`.
- Public release posture for this freeze stays `alpha` only; this matrix must not be used to justify `beta`, `stable`, or production-ready language.
- This matrix is intentionally narrower than the original spec: it tracks the current shipped runtime path, not every future architectural idea already present in the repository.
- Evidence statuses in this matrix describe the current proof level, not a broad public compatibility promise.
- Internal/public alpha sign-off for this freeze assumes `:app:test`, `:app:coverageGate`, `:app:strictSecurity`, `:app:verifyRuntime`, and `:test-harness:test` are all green.
- Public alpha release artifacts are defined by [LAUNCH_CRITERIA.md](LAUNCH_CRITERIA.md) and must include the versioned core runtime jars, the matching bootstrap support jar, harness jar, release checksums, MIT `LICENSE`, third-party notices, release notes/changelog, SBOM, and launcher-generated alpha evidence reports without broadening the frozen scope.
- Compatibility reporting remains a permissive lane for broad boot coverage and must not be read as a replacement for strict-security verification.
- `launch-readiness-report` summarizes required alpha evidence artifact presence, docs scope guardrails, and compatibility report summaries. It does not execute gates or convert synthetic/harness evidence into field evidence.
- `compat-corpus` is the current reproducibility artifact for local compatibility sweeps. It records parsed/unsupported/failed candidates, SHA-256 hashes, entrypoints, dependencies, mixins, and sandbox plans, but it does not claim any candidate has booted.
- `compat-sweep-matrix` links a corpus manifest to stored harness `results.json` outcomes. It normalizes pass/fail/unsupported accounting for planning, but still does not claim gameplay, multiplayer, strict-security, or field evidence. Diagnostics bundles can include this linked view when `--harness-results` is supplied.
- `api-gap-matrix` is the current machine-readable compatibility-surface snapshot for curated Fabric/Forge/NeoForge bridge symbols. It is an alpha triage aid, not a full API parity claim.
- `diagnostics-bundle` is now the expected alpha triage artifact: it contains a manifest, launch-readiness report, compatibility report, compatibility corpus manifest, compatibility sweep matrix, SBOM, API gap matrix, dependency plan, security snapshot, runtime config snapshot, selected logs, and existing runtime evidence reports when present. `InterMedLauncher launch` auto-emits it on non-zero process exit unless explicitly disabled.
- External alpha triage now has dedicated issue templates, [docs/alpha-triage.md](docs/alpha-triage.md), [docs/known-limitations.md](docs/known-limitations.md), and a conservative security reporting policy.
- Modern low-level memory access is now governed through `MEMORY_ACCESS` / legacy `UNSAFE_ACCESS` compatibility, plus explicit `NATIVE_LIBRARY` checks for FFM linker surfaces.
- Direct transformed `System.load/loadLibrary` and `Runtime.load/loadLibrary` calls now route through `NativeLinkerNode`; classloader `findLibrary()` only records canonical ownership diagnostics and does not pre-load native handles.
- Sandbox shared state now carries a structured off-heap execution graph rather than only flat invocation strings.
- Semantic contracts now combine scope heuristics with bytecode signals such as local-state writes, control flow, render API calls, and world mutation calls.
- Polyglot sandboxes stay below `Field-tested` until their runtime behavior is stable without skipped mandatory tests and with broader field validation.
