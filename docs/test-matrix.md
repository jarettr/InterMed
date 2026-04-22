# InterMed v8 Test Matrix

Status: Draft
Applies to: v8.x
Minecraft scope: 1.20.1 only
Loader scope: Fabric, Forge, NeoForge
Java baseline: 21

## 1. Purpose

This document is the compact operational matrix for InterMed v8 testing.

It complements `docs/test-plan-v8-alpha-to-beta.md` by listing the concrete
test areas, case identifiers, automation level, suite placement, mandatory
artifacts, and promotion relevance in a compact reference form.

This document is intended for:

- daily execution
- CI mapping
- suite planning
- beta candidate freezing
- dashboard input generation

## 2. Environment Matrix

### 2.1 Mandatory environment rows

| Env ID | OS | Java | Minecraft | Loader scope | Runtime mode | Security | Machine class |
|---|---|---|---|---|---|---|---|
| ENV-LNX-DED-PERM | Linux x86_64 | 21 | 1.20.1 | Fabric/Forge/NeoForge | dedicated server | permissive | HW-A or HW-B |
| ENV-LNX-DED-STRICT | Linux x86_64 | 21 | 1.20.1 | Fabric/Forge/NeoForge | dedicated server | strict | HW-A or HW-B |
| ENV-WIN-DED-PERM | Windows x86_64 | 21 | 1.20.1 | Fabric/Forge/NeoForge | dedicated server | permissive | HW-A or HW-B |
| ENV-WIN-DED-STRICT | Windows x86_64 | 21 | 1.20.1 | Fabric/Forge/NeoForge | dedicated server | strict | HW-A or HW-B |
| ENV-LNX-CLIENT-SMOKE | Linux x86_64 | 21 | 1.20.1 | mixed smoke | client | permissive/strict as applicable | HW-B |
| ENV-WIN-CLIENT-SMOKE | Windows x86_64 | 21 | 1.20.1 | mixed smoke | client | permissive/strict as applicable | HW-B |

### 2.2 Recommended environment rows

| Env ID | OS | Java | Minecraft | Notes |
|---|---|---|---|---|
| ENV-MAC-CLIENT-SMOKE | macOS x86_64 / arm64 | 21 | 1.20.1 | recommended when available |
| ENV-GRAAL-ESPRESSO | Linux or macOS | GraalVM CE 21 | 1.20.1 | experimental Espresso coverage |

## 3. Artifact Minimums

### 3.1 Required for every non-trivial case

- `run-manifest.json`
- `environment.json`
- `command.txt`
- `exit-status.json`
- `stdout.log`
- `stderr.log`
- `mod-list.json`
- `result.json`

### 3.2 Required by area

| Area | Additional required artifacts |
|---|---|
| boot/startup | `startup-report.json` when available |
| dependency/DAG | `dependency-plan.json` |
| remap | remap fixture output or equivalent evidence file |
| mixin | `mixin-conflict-report.json` when conflict path is exercised |
| registry | `registry-report.json` when registry mediation is exercised |
| security | `security-report.json` |
| VFS/DataPack | `vfs-diagnostics.json` when conflict or reload path is exercised |
| performance | `performance-baseline.json`, `startup-report.json`, optional `jfr.jfr` |
| failure paths | `diagnostics-bundle.zip` |

## 4. Case Catalog Summary

### 4.1 Gates

| ID | Name | Mode | Suite | Beta-critical | Required artifacts |
|---|---|---|---|---|---|
| GATE-001 | clean-checkout-hard-gate | fully-automated | ci-core | yes | required + Gradle summary |
| GATE-002 | harness-self-test | fully-automated | ci-core | yes | required + harness report |
| GATE-003 | launch-kit-generation | fully-automated | ci-core | yes | required + generated output |
| GATE-004 | diagnostics-bundle-on-forced-failure | fully-automated | ci-core | yes | required + diagnostics bundle |

### 4.2 Boot and startup

| ID | Name | Mode | Suite | Beta-critical | Required artifacts |
|---|---|---|---|---|---|
| BOOT-001 | minimal-fabric-dedicated-server | fully-automated | nightly-smoke | yes | required |
| BOOT-002 | minimal-forge-dedicated-server | fully-automated | nightly-smoke | yes | required |
| BOOT-003 | minimal-neoforge-dedicated-server | fully-automated | nightly-smoke | yes | required |
| BOOT-004 | minimal-mixed-loader-dedicated-server | fully-automated | nightly-smoke | yes | required + dependency-plan |
| BOOT-005 | client-smoke-launch | semi-automated | nightly-smoke | yes | required + startup report |
| BOOT-006 | warm-cache-startup-repeat | fully-automated | weekly-perf | no | required + startup report |

### 4.3 Dependency and DAG

| ID | Name | Mode | Suite | Beta-critical | Required artifacts |
|---|---|---|---|---|---|
| DAG-001 | deterministic-dependency-plan | fully-automated | ci-core | yes | required + dependency-plan |
| DAG-002 | parent-peer-weak-peer-wiring | fully-automated | nightly-smoke | yes | required + dependency-plan |
| DAG-003 | private-nested-library-isolation | fully-automated | nightly-smoke | yes | required + dependency-plan |
| DAG-004 | private-library-re-export | fully-automated | nightly-smoke | yes | required + dependency-plan |
| DAG-005 | fallback-discipline | fully-automated | ci-core | yes | required + dependency-plan |

### 4.4 Remap and reflection

| ID | Name | Mode | Suite | Beta-critical | Required artifacts |
|---|---|---|---|---|---|
| REMAP-001 | bytecode-class-member-remap | fully-automated | ci-core | yes | required |
| REMAP-002 | reflection-string-remap | fully-automated | nightly-smoke | yes | required |
| REMAP-003 | unsupported-dynamic-name-diagnostics | semi-automated | nightly-smoke | yes | required |

### 4.5 Mixin

| ID | Name | Mode | Suite | Beta-critical | Required artifacts |
|---|---|---|---|---|---|
| MIXIN-001 | additive-inject-merge | fully-automated | nightly-smoke | yes | required |
| MIXIN-002 | overwrite-conflict-policy | fully-automated | nightly-smoke | yes | required + mixin-conflict-report |
| MIXIN-003 | redirect-wrap-chain-order | fully-automated | nightly-smoke | yes | required |
| MIXIN-004 | public-mod-mixin-corpus-classification | semi-automated | nightly-corpus | yes | required + mixin-conflict-report when applicable |
| MIXIN-005 | safe-fail-for-unsupported-features | semi-automated | nightly-smoke | yes | required + mixin-conflict-report when applicable |

### 4.6 Registry

| ID | Name | Mode | Suite | Beta-critical | Required artifacts |
|---|---|---|---|---|---|
| REG-001 | conflicting-key-sharding | fully-automated | nightly-smoke | yes | required + registry-report |
| REG-002 | global-id-lookup-consistency | fully-automated | nightly-smoke | yes | required + registry-report |
| REG-003 | registry-freeze-behavior | fully-automated | nightly-smoke | yes | required + registry-report |
| REG-004 | mixed-pack-registry-sync | semi-automated | nightly-smoke | yes | required + registry-report |

### 4.7 Network and client/server lifecycle

| ID | Name | Mode | Suite | Beta-critical | Required artifacts |
|---|---|---|---|---|---|
| NET-001 | login-handshake | semi-automated | nightly-smoke | yes | required |
| NET-002 | registry-sync-during-login | semi-automated | nightly-smoke | yes | required |
| NET-003 | custom-payload-roundtrip | semi-automated | nightly-smoke | yes | required |
| NET-004 | short-play-session-smoke | semi-automated | weekly-soak | yes | required |
| NET-005 | disconnect-reconnect-stability | semi-automated | weekly-soak | yes | required |

### 4.8 VFS and DataPack

| ID | Name | Mode | Suite | Beta-critical | Required artifacts |
|---|---|---|---|---|---|
| VFS-001 | resource-overlay-conflict-diagnostics | fully-automated | nightly-smoke | yes | required + vfs-diagnostics |
| VFS-002 | recipe-reload | semi-automated | nightly-smoke | yes | required + vfs-diagnostics when applicable |
| VFS-003 | tag-reload | semi-automated | nightly-smoke | yes | required + vfs-diagnostics when applicable |
| VFS-004 | loot-table-reload | semi-automated | nightly-smoke | yes | required + vfs-diagnostics when applicable |
| VFS-005 | priority-override-merge-policy-sanity | fully-automated | nightly-smoke | yes | required + vfs-diagnostics when applicable |

### 4.9 Security

| ID | Name | Mode | Suite | Beta-critical | Required artifacts |
|---|---|---|---|---|---|
| SEC-001 | strict-denied-file-read | fully-automated | ci-core | yes | required + security-report |
| SEC-002 | strict-allowed-file-read | fully-automated | ci-core | yes | required + security-report |
| SEC-003 | unattributed-sensitive-operation-denied | fully-automated | ci-core | yes | required + security-report |
| SEC-004 | network-connect-denied-allowed | fully-automated | ci-core | yes | required + security-report |
| SEC-005 | process-spawn-denied | fully-automated | ci-core | yes | required + security-report |
| SEC-006 | reflection-access-denied-diagnosed | fully-automated | ci-core | yes | required + security-report |
| SEC-007 | native-library-routing-conflict-diagnostics | semi-automated | nightly-smoke | yes | required + security-report |
| SEC-008 | async-attribution-propagation | fully-automated | ci-core | yes | required + security-report |
| SEC-009 | tccl-propagation | semi-automated | nightly-smoke | yes | required + security-report |
| SEC-010 | sandbox-denial-and-grant-behavior | semi-automated | nightly-smoke | yes | required + security-report |

### 4.10 Native

| ID | Name | Mode | Suite | Beta-critical | Required artifacts |
|---|---|---|---|---|---|
| NATIVE-001 | jni-jna-smoke-fixture | semi-automated | nightly-smoke | yes | required |
| NATIVE-002 | custom-scheduler-async-fixture | semi-automated | nightly-smoke | yes | required |

### 4.11 Corpus

| ID | Name | Mode | Suite | Beta-critical | Required artifacts |
|---|---|---|---|---|---|
| CORPUS-001 | single-mod-corpus | automated/sharded | nightly-corpus | yes | required + compatibility-report |
| CORPUS-002 | pair-mod-corpus | automated/sharded | nightly-corpus | yes | required + compatibility-report |
| CORPUS-003 | curated-slice-packs | automated or semi-automated | nightly-corpus | yes | required + compatibility-report |

### 4.12 Performance

| ID | Name | Mode | Suite | Beta-critical | Required artifacts |
|---|---|---|---|---|---|
| PERF-001 | native-baseline-capture | semi-automated | weekly-perf | yes | required + performance-baseline |
| PERF-002 | intermed-startup-baseline | semi-automated | weekly-perf | yes | required + performance-baseline + startup-report |
| PERF-003 | tick-time-baseline | semi-automated | weekly-perf | yes | required + performance-baseline |
| PERF-004 | memory-baseline | semi-automated | weekly-perf | yes | required + performance-baseline |
| PERF-005 | gc-pause-baseline | semi-automated | weekly-perf | yes | required + performance-baseline |

### 4.13 Soak

| ID | Name | Mode | Suite | Beta-critical | Required artifacts |
|---|---|---|---|---|---|
| SOAK-001 | short-dedicated-server-soak | automated | weekly-soak | yes | required |
| SOAK-002 | mixed-pack-medium-soak | semi-automated | weekly-soak | yes | required |
| SOAK-003 | long-soak | deferred | deferred | no | required if run |

## 5. Frozen Beta Candidate Set Rules

The frozen beta candidate set must be versioned and explicitly tracked.

For the frozen beta candidate set:

- `not-run` must equal zero
- every `fail` must be triaged
- every `unsupported` must be documented
- required artifacts must exist for every case
- expired waivers are treated as failures

## 6. Beta Dashboard Inputs

Each suite run must emit:

- `suite-summary.json`
- pass/fail/unsupported/not-run/blocked counts
- first failing case ids
- artifact index
- environment descriptor
- machine class

These files are inputs for the readiness dashboard rules document.

The current dashboard generator is `testing/promote_results.py`. It reads
`suite-summary.json` files, `testing/frozen-beta-candidate.json`, and
`testing/waivers.json`, then writes a machine-readable beta readiness summary.

## 7. Comparison Rules

All cross-run comparisons must label:

- exploratory corpus vs frozen corpus
- cold cache vs warm cache
- strict vs permissive
- Linux vs Windows
- HW-A vs HW-B

Comparisons without these labels are not valid for release-quality reasoning.

## 8. Release-Blocking Areas

The following matrix rows are release-blocking for public beta wording:

- all hard gates
- BOOT-001 through BOOT-005
- DAG-001 through DAG-005
- REG-004
- NET-001 through NET-003
- VFS-002 through VFS-004
- SEC-001 through SEC-009
- PERF-001 through PERF-005
- SOAK-001 and SOAK-002

## 9. Notes

This matrix is intentionally operational and compact. The reasoning,
definitions, waiver policy, and promotion criteria remain in
`docs/test-plan-v8-alpha-to-beta.md`.
