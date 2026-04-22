# InterMed v8 Test Plan: Alpha -> Beta

Status: Draft
Applies to: v8.x
Minecraft scope: 1.20.1 only
Loader scope: Fabric, Forge, NeoForge
Java baseline: 21

## 1. Purpose

This document defines how InterMed is tested during the transition from open
alpha toward beta-quality internal readiness.

The goal of this plan is to prevent vague launch language, hidden gaps, and
unverifiable confidence. A feature is not treated as tested merely because code
exists, because one manual run looked fine, or because a permissive
compatibility sweep returned green. A feature is treated as tested only when the
required scenario was executed, required artifacts were captured, and the final
outcome was classified.

This document defines:

- evidence levels
- test environments
- artifact requirements
- outcome vocabulary
- test suites and cadence
- test area scenarios
- automation boundaries
- beta candidate rules
- promotion criteria
- waiver and exception handling
- release-blocking behavior

## 2. Non-Claims

Until separately proven, this document does not imply:

- production stability
- a compatibility percentage
- full Fabric, Forge, or NeoForge API parity
- general `1.20+` support
- field-proven hostile-mod security
- a fixed public overhead number

Public language must remain narrower than internal progress.

## 3. Evidence Model

InterMed uses the following evidence statuses:

| Status | Meaning |
|---|---|
| Implemented | Code exists but may not be wired or meaningfully exercised |
| Wired | Connected to the real runtime path but only minimally proven |
| Synthetic-tested | Proven through unit tests, integration fixtures, or synthetic mods |
| Harness-tested | Exercised by the compatibility harness against external artifacts |
| Field-tested | Exercised in real client/server or modpack scenarios outside narrow harness assumptions |
| Deferred | Intentionally outside the current claim surface |

No area may be promoted above the evidence level actually demonstrated by
stored artifacts.

## 4. Runtime Scope

### 4.1 Frozen scope for v8.x beta-prep

- Minecraft: `1.20.1` only
- Java: `21`
- Loader families: `Fabric`, `Forge`, `NeoForge`
- Runtime path: `InterMedKernel -> LifecycleManager -> LazyInterMedClassLoader`

This plan must not be read as support for later Minecraft versions or broader
loader claims.

## 5. Test Environment Matrix

### 5.1 Mandatory runtime matrix

| Dimension | Required |
|---|---|
| Minecraft | 1.20.1 only |
| Java | Temurin/OpenJDK 21 |
| OS | Linux x86_64, Windows x86_64 |
| Loader families | Fabric, Forge, NeoForge |
| Runtime modes | dedicated server mandatory, client smoke mandatory |
| Security modes | strict and permissive |
| Artifact source types | synthetic fixtures, curated external mods, mixed mini-packs |

### 5.2 Optional but recommended matrix

| Dimension | Recommended |
|---|---|
| Java | GraalVM CE 21 for Espresso coverage |
| OS | macOS x86_64 / arm64 when available |
| Hardware class | low-memory runner and normal desktop |
| Session type | local and CI |

### 5.3 Hardware classes

All performance artifacts must record hardware class.

#### HW-A: low resource

- 4 cores
- 8 GB RAM
- SSD
- 768 MB to 2 GB per test JVM depending on lane

#### HW-B: normal desktop

- 6 to 8 cores
- 16+ GB RAM
- SSD
- 2 GB to 6 GB per test JVM depending on lane

## 6. Artifact Contract

Every non-trivial run must emit artifacts to a stable directory.

### 6.1 Directory convention

`build/test-runs/<YYYYMMDD>/<suite>/<case-id>/`

Example:

`build/test-runs/20260422/nightly/NET-002/`

### 6.2 Required files per case

Every case must attempt to emit:

- `run-manifest.json`
- `environment.json`
- `command.txt`
- `exit-status.json`
- `stdout.log`
- `stderr.log`
- `mod-list.json`
- `result.json`

### 6.3 Conditional files

Emit when applicable:

- `diagnostics-bundle.zip`
- `compatibility-report.json`
- `compat-corpus.json`
- `compat-sweep-matrix.json`
- `api-gap-matrix.json`
- `dependency-plan.json`
- `security-report.json`
- `hostile-smoke.txt`
- `performance-baseline.json`
- `native-loader-baseline.json`
- `startup-report.json`
- `vfs-diagnostics.json`
- `mixin-conflict-report.json`
- `registry-report.json`
- `jfr.jfr`

### 6.4 Artifact rules

- Missing required artifact = case failure unless the case is explicitly marked `manual-observation-only`
- A non-zero launched process exit must automatically attempt `diagnostics-bundle.zip`
- "Pass" without artifact is invalid
- "Not run" must be recorded explicitly and never silently omitted
- Artifact presence alone is not evidence unless a final classification exists in `result.json`

## 7. Outcome Vocabulary

Every case must end in exactly one of:

- `pass`
- `fail`
- `unsupported`
- `not-run`
- `blocked`

### Definitions

#### pass

The scenario met the pass criteria and required artifacts exist.

#### fail

The scenario executed and violated pass criteria.

#### unsupported

The scenario exercised a known unsupported feature, and the unsupported status
is intentional and documented.

#### not-run

The scenario was in scope for the suite, but no execution occurred.

#### blocked

The scenario could not run because a prerequisite suite or environment failed
first.

## 8. Automation Levels

### 8.1 Allowed automation levels

- `fully-automated`
- `semi-automated`
- `manual-observation-only`

### 8.2 Policy

- Fully automated is preferred for all beta-critical cases where practical
- Semi-automated is allowed only for explicitly listed lifecycle, client, native, or edge-path cases
- Manual-observation-only is forbidden for beta promotion unless covered by an approved waiver
- A semi-automated case is still invalid without artifacts and final classification

## 9. Verification Lanes

### 9.1 Existing mandatory gates

These are hard gates and must remain green:

```bash
./gradlew :app:test :app:coverageGate :app:strictSecurity :app:verifyRuntime --rerun-tasks -Dintermed.allowRemoteForgeRepo=true --console=plain
./gradlew :test-harness:test --rerun-tasks --console=plain
```

### 9.2 Suite cadence

| Suite | Cadence | Goal |
|---|---|---|
| pre-commit | local | cheap regression and syntax confidence |
| ci-core | every push/PR | mandatory hard gate |
| nightly-smoke | nightly | external boot and lifecycle coverage |
| nightly-corpus | nightly or sharded nightly | classify curated external mods |
| weekly-perf | weekly | collect startup/TPS/memory/GC baselines |
| weekly-soak | weekly | medium stability confidence |
| release-candidate | before public tag | final artifact bundle and promotion check |

## 10. Ownership and Sources of Truth

This document defines the overall policy. Operational ownership is split by
area.

### 10.1 Area ownership

| Area | Primary owner |
|---|---|
| build/launch gates | Gradle + launcher wrappers |
| boot/startup | harness |
| DAG/dependency | harness + runtime verification |
| remap/reflection | app verification fixtures |
| mixin classification | harness + reporting |
| registry virtualization | app verification + client/server smoke |
| networking lifecycle | semi-automated client/server smoke |
| VFS/DataPack lifecycle | client or lifecycle smoke |
| strict security | `:app:strictSecurity` |
| native/TCCL | dedicated fixtures and smoke runs |
| performance baselines | performance runner |
| soak | soak runner |
| beta dashboard state | readiness rules document |

### 10.2 Source of truth

- Scenario definitions: `testing/test-cases.json`
- Suite orchestration: `testing/*.sh`
- Artifact directories: `build/test-runs/...`
- Promotion decision rules: `docs/beta-readiness-dashboard-rules.md`

## 11. Test Areas and Scenarios

### 11.1 Build and launch gates

#### GATE-001 Clean checkout hard gate

- Mode: fully automated
- Entrypoint: Gradle
- Pass: all hard-gate tasks green from clean checkout
- Artifact: combined Gradle logs, task summary, coverage report

#### GATE-002 Harness self-test

- Mode: fully automated
- Entrypoint: Gradle
- Pass: all harness tests green
- Artifact: harness test report

#### GATE-003 Launch-kit generation

- Mode: fully automated
- Entrypoint: launcher
- Pass: generated snippets and wrapper scripts exist and parse cleanly
- Artifact: generated launch-kit output

#### GATE-004 Diagnostics bundle on forced failure

- Mode: fully automated
- Entrypoint: launcher
- Pass: intentional failed launch produces `diagnostics-bundle.zip`
- Fail: non-zero exit with no diagnostics bundle

### 11.2 Boot and startup compatibility

#### BOOT-001 Minimal Fabric dedicated server

- Mode: fully automated
- Pass: process starts, reaches ready state, exits cleanly

#### BOOT-002 Minimal Forge dedicated server

- Mode: fully automated
- Pass: process starts, reaches ready state, exits cleanly

#### BOOT-003 Minimal NeoForge dedicated server

- Mode: fully automated
- Pass: process starts, reaches ready state, exits cleanly

#### BOOT-004 Minimal mixed-loader dedicated server

- Goal: prove the canonical runtime path survives a mixed minimal pack
- Pass: start, dependency plan produced, no fatal classloader or mixin failure

#### BOOT-005 Client smoke launch

- Mode: semi-automated
- Pass: client reaches main menu without fatal crash

#### BOOT-006 Warm-cache startup repeat

- Mode: fully automated
- Goal: compare cold vs warm AOT cache startup
- Pass: both runs complete and startup artifacts are stored

### 11.3 Dependency resolution and DAG classloading

#### DAG-001 Deterministic dependency plan

- Mode: fully automated
- Pass: normalized `dependency-plan.json` is stable across repeated runs

#### DAG-002 Parent/peer/weak-peer wiring

- Mode: fully automated
- Pass: loader topology matches expected graph for known fixtures

#### DAG-003 Private nested library isolation

- Mode: fully automated
- Pass: distinct class identities and loader identities are preserved

#### DAG-004 Private library re-export

- Mode: fully automated
- Pass: dependant resolves exported symbol through intended path

#### DAG-005 Fallback discipline

- Mode: fully automated
- Pass: strict production-style suites do not silently rely on degraded fallback paths

### 11.4 Remapping and reflection

#### REMAP-001 Bytecode class/member remap

- Mode: fully automated
- Pass: synthetic remap fixture runs with expected translated behavior

#### REMAP-002 Reflection string remap

- Mode: fully automated
- Pass: supported lookup paths resolve correctly

#### REMAP-003 Unsupported dynamic-name cases are diagnosable

- Mode: semi-automated
- Pass: risky or untranslated case is logged or classified, not silently treated as supported

### 11.5 Mixin behavior

#### MIXIN-001 Additive inject merge

- Mode: fully automated
- Pass: multiple compatible injectors execute in expected order

#### MIXIN-002 Overwrite conflict policy

- Mode: fully automated
- Pass: exact selected strategy recorded in `mixin-conflict-report.json`

#### MIXIN-003 Redirect / wrap chain order

- Mode: fully automated
- Pass: wrappers and redirects execute in documented order

#### MIXIN-004 Public-mod mixin corpus classification

- Mode: semi-automated, then automated reporting
- Pass: every beta-candidate mixin artifact lands in one bucket:
- `supported`
- `supported with caveats`
- `safe-fail`
- `unsupported`

#### MIXIN-005 Safe-fail for unsupported features

- Mode: semi-automated
- Pass: unsupported features produce explicit failure or bridge outcome, not silent corruption

### 11.6 Registry virtualization

#### REG-001 Conflicting key sharding

- Mode: fully automated
- Pass: two mods can register same logical key without cross-contamination

#### REG-002 Global ID lookup consistency

- Mode: fully automated
- Pass: global/raw lookup maps back to original mod-local identity correctly

#### REG-003 Registry freeze behavior

- Mode: fully automated
- Pass: late registration after freeze fails deterministically and diagnostically

#### REG-004 Mixed-pack registry sync

- Mode: semi-automated
- Pass: client/server registry state matches after login and sync

### 11.7 Network and client/server lifecycle

#### NET-001 Login handshake

- Mode: semi-automated
- Pass: mixed client/server pair reaches successful login

#### NET-002 Registry sync during login

- Mode: semi-automated
- Pass: registry handshake completes without mismatch

#### NET-003 Custom payload roundtrip

- Mode: semi-automated
- Pass: synthetic payload request/response succeeds end-to-end

#### NET-004 Short play-session smoke

- Mode: semi-automated
- Duration: 3 to 5 minutes
- Pass: player can join, tick, interact, and disconnect cleanly

#### NET-005 Disconnect/reconnect stability

- Mode: semi-automated
- Pass: second connection succeeds without stale-state contamination

### 11.8 VFS and DataPack lifecycle

#### VFS-001 Resource overlay conflict diagnostics

- Mode: fully automated
- Pass: diagnostics file is generated and populated when expected

#### VFS-002 Recipe reload

- Mode: semi-automated
- Pass: datapack reload preserves expected recipes

#### VFS-003 Tag reload

- Mode: semi-automated
- Pass: tags resolve correctly after reload

#### VFS-004 Loot table reload

- Mode: semi-automated
- Pass: loot tables resolve and behave after reload

#### VFS-005 Priority override / merge policy sanity

- Mode: fully automated
- Pass: known fixtures produce expected winner or merged result

### 11.9 Security and capability enforcement

All security testing must run in two separate lanes:

- permissive compatibility
- strict fail-closed

Permissive success never counts as security proof.

#### SEC-001 Strict denied file read

- Mode: fully automated
- Pass: denied read throws and records mod/capability/scope/reason/action

#### SEC-002 Strict allowed file read

- Mode: fully automated
- Pass: granted read succeeds

#### SEC-003 Unattributed sensitive operation denied

- Mode: fully automated
- Pass: unattributed caller is denied or explicitly diagnosed in strict mode

#### SEC-004 Network connect denied/allowed

- Mode: fully automated
- Pass: denied host blocked, allowed host succeeds

#### SEC-005 Process spawn denied

- Mode: fully automated
- Pass: strict mode blocks ungranted process spawn

#### SEC-006 Reflection access denied/diagnosed

- Mode: fully automated
- Pass: sensitive reflective access is blocked or clearly reported

#### SEC-007 Native library routing and conflict diagnostics

- Mode: semi-automated
- Pass: repeated same-library load dedupes; conflicting ownership emits diagnostics

#### SEC-008 Async attribution propagation

- Mode: fully automated
- Pass: supported wrapped async paths preserve attribution

#### SEC-009 TCCL propagation

- Mode: semi-automated
- Pass: TCCL remains correct across supported async fixture paths

#### SEC-010 Sandbox denial and grant behavior

- Mode: semi-automated
- Pass: ungranted sandbox code fails closed; granted scope works

### 11.10 Native and host integration

#### NATIVE-001 JNI/JNA smoke fixture

- Mode: semi-automated
- Pass: native library load path is deterministic and diagnosable

#### NATIVE-002 Custom scheduler async fixture

- Mode: semi-automated
- Pass: path either propagates correctly or is explicitly marked unsupported

### 11.11 Corpus compatibility

#### CORPUS-001 Single-mod corpus

- Mode: automated, sharded
- Goal: classify a curated external mod corpus one artifact at a time

#### CORPUS-002 Pair-mod corpus

- Mode: automated, sharded
- Goal: classify selected high-risk or high-value pairs

#### CORPUS-003 Curated slice packs

- Mode: automated or semi-automated
- Goal: test mini-packs around one subsystem:
- mixin-heavy
- registry-heavy
- networking-heavy
- datapack-heavy
- native-heavy

### Corpus rules

For the beta candidate set:

- every entry must end as `pass`, `fail`, or `unsupported`
- `not-run` is not allowed
- every `fail` must have diagnostics
- every `unsupported` must reference a known limitation or issue

### 11.12 Performance

#### PERF-001 Native baseline capture

- Mode: semi-automated
- Goal: capture native Fabric and native Forge baseline on same machine class and scenario

#### PERF-002 InterMed startup baseline

- Mode: semi-automated
- Pass: startup timing stored for same minimal pack as native baseline

#### PERF-003 Tick-time baseline

- Duration: 5 to 10 minutes
- Metrics: avg tick, p95, p99

#### PERF-004 Memory baseline

- Metrics: heap high-water mark, metaspace, full GC count

#### PERF-005 GC pause baseline

- Metrics: max pause, p95 pause, pause counts above threshold

### Performance rules

- Baseline comparison must use same machine class, same Java version, same pack, same scenario
- Warm-cache and cold-cache data must be labeled separately
- Microbenchmarks are internal hot-path evidence only
- No public performance statement may combine synthetic microbench data with real scenario baselines
- No cherry-picked best-case result may be presented as general behavior

### 11.13 Soak

#### SOAK-001 Short dedicated server soak

- Mode: automated
- Duration: 30 minutes
- Pass: no crash, no runaway memory growth, no registry/network corruption

#### SOAK-002 Mixed-pack medium soak

- Mode: semi-automated
- Duration: 2 hours
- Pass: stable session, reconnect possible, no untriaged severe regressions

#### SOAK-003 Long soak

- Mode: deferred until later stable-prep stage
- Duration: 12 to 48 hours
- Status: not required for initial beta language

## 12. Automation Design

Automation must remain layered.

### 12.1 Existing entrypoints

Use existing Gradle tasks and current harness/launcher split as the source of
truth.

### 12.2 Proposed wrapper scripts

Introduce thin wrappers under `testing/`:

- `testing/run_local_gates.sh`
- `testing/run_smoke_suite.sh`
- `testing/run_corpus_shard.sh`
- `testing/run_client_server_smoke.sh`
- `testing/run_perf_baseline.sh`
- `testing/run_soak.sh`
- `testing/import_gradle_evidence.py`
- `testing/import_harness_boot_evidence.py`
- `testing/collect_artifacts.sh`
- `testing/promote_results.py`

Wrappers orchestrate existing tasks; they do not replace them.

### 12.2.1 Current wrapper status

The initial beta-prep skeleton now includes:

- `testing/_case_runner.py`
- `testing/run_local_gates.sh`
- `testing/run_smoke_suite.sh`
- `testing/run_client_server_smoke.sh`
- `testing/run_security_suite.sh`
- `testing/run_corpus_shard.sh`
- `testing/run_perf_baseline.sh`
- `testing/run_soak.sh`
- `testing/import_gradle_evidence.py`
- `testing/import_harness_boot_evidence.py`
- `testing/collect_artifacts.sh`
- `testing/promote_results.py`
- `testing/frozen-beta-candidate.json`
- `testing/waivers.json`

Wrappers may emit `not-run` or `blocked` for beta-critical rows when the
required scenario is not yet honestly automated. A supporting metadata,
synthetic, or unit-test artifact must not be promoted to beta evidence for a
dedicated server, real client/server lifecycle, real datapack reload, or real
performance baseline unless the required scenario itself ran.

### 12.3 Proposed machine-readable test catalog

Maintain `testing/test-cases.json` with entries like:

```json
[
  {
    "id": "BOOT-001",
    "area": "boot",
    "name": "minimal-fabric-dedicated-server",
    "mode": "fully-automated",
    "suite": "nightly-smoke",
    "entrypoint": "harness",
    "platform": "fabric",
    "required_artifacts": [
      "run-manifest.json",
      "stdout.log",
      "stderr.log",
      "result.json"
    ],
    "pass_rule": "server_ready == true && exit_code == 0"
  }
]
```

### 12.4 Runner contract

Every wrapper must:

- create case directory
- record command and environment
- normalize final outcome
- collect artifacts
- emit one-line summary into suite index

### 12.5 Suite summary file

Each suite run must emit:

`build/test-runs/<date>/<suite>/suite-summary.json`

Including:

- total cases
- pass count
- fail count
- unsupported count
- not-run count
- blocked count
- first failing case ids
- artifact index

## 13. Semi-Manual Procedure

### 13.1 Semi-manual cases currently allowed

- client visual/menu entry
- short play session
- datapack reload verification in real client lifecycle
- native-heavy external fixtures
- custom scheduler/TCCL edge cases

### 13.2 Minimum requirements

A semi-manual case is valid only if it records:

- exact environment
- exact mod list
- start and end timestamps
- observer notes
- logs
- final classification

Screenshots or video are optional supporting artifacts, never sole evidence.

## 14. Beta Candidate Set

Define a fixed beta candidate set and keep it versioned.

Recommended minimum composition:

- 20 mandatory smoke cases
- 30 mandatory security/native/VFS/remap cases
- 50 curated external single-mod cases
- 20 curated pair cases
- 5 curated slice packs
- 2 client/server lifecycle scenarios
- 2 performance baseline scenarios
- 2 soak scenarios

For this beta candidate set:

- `not-run` must be zero
- all failures must be triaged
- all unsupported outcomes must be documented
- all mandatory artifacts must exist

This creates a concrete internal gate without forcing a public compatibility
percentage.

## 15. Frozen Beta Corpus

Two corpus layers must exist:

### 15.1 Exploratory corpus

A moving set used for broad discovery and experimentation.

### 15.2 Frozen beta corpus

A versioned fixed set used for comparative readiness reporting and beta gating.

Rules:

- the frozen beta corpus must not change without a recorded update
- weekly comparisons must identify whether numbers came from exploratory or frozen corpus
- promotion decisions must reference the frozen beta corpus, not exploratory results

## 16. Waiver Policy

Waivers are exceptions, not silent omissions.

### 16.1 What may be waived

- temporarily broken semi-manual case
- infrastructure-specific runner failure
- known external artifact unavailability
- dated coverage exception with removal plan

### 16.2 What may not be waived for beta wording

- missing hard gate
- missing diagnostics on failing launch
- zero client/server lifecycle evidence
- missing strict security lane for mandatory cases
- unexplained `not-run` cases in the frozen beta candidate set

### 16.3 Waiver requirements

Every waiver must record:

- case id or gate id
- reason
- owner
- approval date
- expiry date
- mitigation plan
- linked issue

Expired waivers are treated as failures.

## 17. Promotion Criteria

### 17.1 Allowed alpha language

Allowed when:

- hard gates green
- smoke suites mostly operational
- external reports are triageable
- diagnostics bundles work
- compatibility accounting exists, even if incomplete

### 17.2 Internal beta readiness gate

InterMed may be treated as internally beta-ready only when all of the following
are true:

1. current beta candidate set has zero `not-run`
2. all mandatory hard gates are green
3. Linux and Windows mandatory smoke suites are green
4. client/server login, registry sync, and payload smoke pass
5. VFS/DataPack recipe/tag/loot reload cases pass
6. strict security mandatory cases pass
7. native/TCCL mandatory cases are either passing or explicitly marked unsupported
8. corpus results are published as `pass` / `fail` / `unsupported`
9. package-level coverage gates for core, security, registry, remapping are active at 60-70% minimum, or every exception has a dated removal plan
10. native baseline and InterMed baseline artifacts exist for startup, tick, heap/metaspace, and GC pause

### 17.3 Public beta language gate

Public beta language is allowed only after internal beta readiness is achieved
and the readiness dashboard shows no release-blocking unresolved red status.

### 17.4 Stable language remains forbidden

Even after beta:

- no production-ready language
- no compatibility percentage
- no hostile-mod guarantee
- no general `1.20+` statement

## 18. Release Blocking Rules

The following block a beta label or public beta wording:

- failing hard gates
- missing suite summaries for frozen beta suites
- any `not-run` in frozen beta candidate set
- missing artifacts for mandatory cases
- unresolved client/server smoke failure
- unresolved strict security failure
- unresolved diagnostics-bundle failure on forced crash path
- missing performance baseline artifacts for required beta scenarios

The following do not automatically block beta wording if documented and triaged:

- exploratory corpus failures outside frozen beta corpus
- explicit `unsupported` cases already documented
- deferred long soak
- experimental lanes outside beta contract

## 19. Reporting Format

Every public progress report should include:

- suite name
- date
- machine class
- environment
- pass/fail/unsupported/not-run counts
- newly discovered unsupported scenarios
- newly fixed failures
- artifact links

Do not say "compatibility improved" without matrix row counts.

## 20. Immediate Implementation Order

### Phase 1: lock the skeleton

- create `docs/test-plan-v8-alpha-to-beta.md`
- create `docs/test-matrix.md`
- create `testing/test-cases.json`
- create `testing/run_local_gates.sh`
- create `testing/collect_artifacts.sh`

### Phase 2: automate core smoke

- automate GATE, BOOT, DAG, REMAP, REG, SEC minimum set
- emit per-case directories and suite summaries

### Phase 3: automate corpus and baseline

- sharded corpus runner
- native baseline capture
- InterMed baseline capture
- nightly suite summaries

### Phase 4: semi-automate lifecycle

- login
- registry sync
- payload roundtrip
- datapack reload

### Phase 5: enforce beta candidate set

- freeze candidate list
- eliminate `not-run`
- require artifact completeness
- start beta readiness reporting
