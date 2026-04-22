# InterMed v8 Beta Readiness Dashboard Rules

Status: Draft
Applies to: v8.x
Minecraft scope: 1.20.1 only
Loader scope: Fabric, Forge, NeoForge
Java baseline: 21

## 1. Purpose

This document defines the compact operational rules used to decide whether
InterMed may move from open alpha claim posture toward public beta wording.

It does not replace the master test plan. It translates test outcomes into
release readiness signals.

This document answers:

- what must be green
- what may remain unsupported
- what may be waived
- what blocks public beta wording
- what must be reported with every readiness update

## 2. Readiness States

InterMed uses four internal readiness states:

### 2.1 Alpha-active

Open alpha is public. Core gates exist, but beta requirements are not yet met.

### 2.2 Beta-prep

The project is actively driving the frozen beta candidate set toward
completeness.

### 2.3 Internal beta-ready

The beta gate is met internally. Public wording may still remain alpha if
release leadership chooses not to widen claims yet.

### 2.4 Public beta-eligible

Internal beta-ready plus no unresolved release-blocking red conditions. Public
beta wording is allowed.

## 3. Dashboard Colors

### Green

Requirement is fully satisfied with required artifacts.

### Yellow

Requirement is partially satisfied, degraded, or satisfied only outside the
frozen beta set.

### Red

Requirement is not satisfied and blocks internal beta-ready or public
beta-eligible status.

### Gray

Deferred or out of scope for the current release posture.

## 4. Top-Level Dashboard Sections

The dashboard must at minimum show:

- hard gates
- frozen beta candidate set
- mandatory smoke suites
- client/server lifecycle
- VFS/DataPack lifecycle
- strict security
- native/TCCL
- frozen corpus status
- performance baselines
- soak baselines
- waiver count
- unresolved blocker count

## 5. Green Rules

A section is green only if all its required rows are green and all required
artifacts exist.

### 5.1 Hard gates green when

- `:app:test` green
- `:app:coverageGate` green
- `:app:strictSecurity` green
- `:app:verifyRuntime` green
- `:test-harness:test` green

### 5.2 Frozen beta candidate set green when

- zero `not-run`
- zero missing mandatory artifacts
- every `fail` triaged
- every `unsupported` documented
- zero expired waivers

### 5.3 Mandatory smoke green when

- Linux mandatory smoke suite green
- Windows mandatory smoke suite green
- BOOT-001 through BOOT-005 all pass

### 5.4 Client/server lifecycle green when

- NET-001 pass
- NET-002 pass
- NET-003 pass

NET-004 and NET-005 may be yellow during beta-prep but must be green for public
beta-eligible status.

### 5.5 VFS/DataPack green when

- VFS-002 pass
- VFS-003 pass
- VFS-004 pass

VFS-001 and VFS-005 may support diagnosis and policy confidence but do not
replace real reload evidence.

### 5.6 Strict security green when

- SEC-001 through SEC-006 pass
- SEC-008 pass
- strict lane artifacts exist
- no permissive-only result is being treated as security proof

SEC-007, SEC-009, SEC-010 may be green or documented unsupported depending on
release scope.

### 5.7 Native/TCCL green when

- NATIVE-001 pass or documented unsupported
- NATIVE-002 pass or documented unsupported
- SEC-007 and SEC-009 classified with artifacts

### 5.8 Frozen corpus green when

- all frozen beta corpus entries classified
- zero `not-run`
- every fail triaged
- every unsupported linked to a limitation or issue

### 5.9 Performance green when

- native baseline artifacts exist
- InterMed baseline artifacts exist
- startup, tick, heap/metaspace, and GC pause data all exist
- all comparisons use identical scenario definitions and machine class labels

Performance may be green without any public overhead claim.

### 5.10 Soak green when

- SOAK-001 pass
- SOAK-002 pass

SOAK-003 remains gray unless explicitly promoted into scope later.

## 6. Yellow Rules

A section is yellow when:

- evidence exists but is incomplete
- only exploratory corpus results exist
- the required case exists but has temporary non-blocking degradation
- a non-expired waiver exists
- the section is not yet part of public beta wording but is being worked

Examples:

- NET-004 not yet passing while NET-001..003 are green
- macOS not yet covered
- exploratory corpus improving but frozen corpus incomplete
- one temporary semi-manual case under approved waiver

## 7. Red Rules

A section is red when any release-blocking requirement fails.

Automatic red conditions include:

- failing hard gate
- missing mandatory artifacts
- any `not-run` in frozen beta candidate set
- unresolved client/server smoke failure
- unresolved strict security failure
- unresolved diagnostics bundle generation failure
- missing frozen corpus classification for mandatory entries
- missing required performance baseline artifacts
- expired waiver on beta-critical case

## 8. Gray Rules

Gray is allowed only for:

- explicitly deferred long soak
- out-of-scope experimental lanes
- optional matrix rows not required for current claim posture

Gray must never hide a required beta-critical row.

## 9. Waiver Handling

### 9.1 Waiver visibility

The dashboard must show:

- active waiver count
- expired waiver count
- waiver ids
- affected case ids
- expiry dates

### 9.2 Waiver effect

- active waiver = yellow unless the waived row is non-blocking
- expired waiver = red
- waiver does not convert missing execution into pass
- waiver does not allow silent omission of artifacts

### 9.3 Non-waivable blockers

The following may not be waived for public beta wording:

- hard gate failure
- missing diagnostics bundle generation on forced failure path
- missing strict security results for mandatory rows
- missing client/server handshake evidence
- any `not-run` in frozen beta candidate set

## 10. Public Wording Rules

### 10.1 Allowed alpha wording

Allowed if:

- hard gates green
- diagnostics bundles work
- compatibility accounting exists
- smoke coverage exists, even if incomplete

### 10.2 Allowed beta wording

Allowed only if:

- internal beta-ready is achieved
- no red section remains in required beta rows
- dashboard summary is publishable with artifact links
- frozen beta candidate set is complete

### 10.3 Forbidden wording even after beta

Still forbidden:

- production-ready
- compatibility percentage
- hostile-mod guarantee
- general `1.20+` support
- broad API parity claim

## 11. Internal Beta-Ready Definition

InterMed is internally beta-ready only when all required dashboard sections are
green:

- hard gates
- frozen beta candidate set
- mandatory smoke suites
- client/server lifecycle minimum
- VFS/DataPack reload minimum
- strict security minimum
- frozen corpus accounting
- performance baseline artifact set
- soak minimum

## 12. Public Beta-Eligible Definition

InterMed is public beta-eligible only when:

- internal beta-ready is true
- blocker count = 0
- expired waiver count = 0
- required artifact completeness = 100%
- release report for the frozen beta candidate set has been generated

## 13. Required Dashboard Output

Each dashboard refresh must include:

- date
- commit or tag
- machine class(es)
- environment ids covered
- total pass/fail/unsupported/not-run/blocked
- blocker count
- waiver count
- first failing case ids
- links to suite summaries
- links to release-critical artifacts

## 14. Minimal Dashboard Summary Format

Recommended summary fields:

```json
{
  "state": "beta-prep",
  "date": "2026-04-22",
  "commit": "<sha>",
  "frozen_beta_candidate": {
    "pass": 0,
    "fail": 0,
    "unsupported": 0,
    "not_run": 0,
    "blocked": 0
  },
  "blockers": [],
  "waivers": [],
  "sections": {
    "hard_gates": "green",
    "mandatory_smoke": "yellow",
    "client_server": "yellow",
    "vfs_datapack": "yellow",
    "strict_security": "green",
    "frozen_corpus": "yellow",
    "performance": "yellow",
    "soak": "yellow"
  }
}
```

## 15. Reporting Rules

Every public or internal readiness report must include:

- suite name
- date
- machine class
- environment
- pass/fail/unsupported/not-run counts
- newly discovered unsupported scenarios
- newly fixed failures
- links to artifacts or suite summaries

Do not use vague phrases such as:

- "compatibility improved"
- "security is better"
- "performance is better"

unless the changed row counts or baseline results are shown.

## 16. Immediate Implementation Steps

1. create this document at `docs/beta-readiness-dashboard-rules.md`
2. implement `suite-summary.json` emission for all suites
3. implement frozen beta candidate tracking
4. implement waiver file or waiver registry
5. generate one machine-readable dashboard summary per nightly and per release-candidate run
6. make beta wording depend on dashboard state, not gut feeling

Current implementation status:

- `testing/collect_artifacts.sh` emits `suite-summary.json`
- `testing/import_gradle_evidence.py` imports existing Gradle/JUnit reports into case artifacts
- `testing/import_harness_boot_evidence.py` imports real harness dedicated-server boot results into BOOT case artifacts
- `testing/frozen-beta-candidate.json` tracks the draft frozen candidate set
- `testing/waivers.json` is the waiver registry
- `testing/promote_results.py` generates the dashboard summary
- beta wording remains blocked until the dashboard reaches the required green state
