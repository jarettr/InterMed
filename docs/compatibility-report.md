# InterMed v8.0 — Compatibility Testing Report

> **Run date:** 2026-04-13
> **InterMed version:** 8.0-SNAPSHOT
> **MC version:** 1.20.1
> **Forge:** 47.3.0 · **Fabric Loader:** 0.19.1
> **Machine:** Linux x86\_64, Temurin JDK 21.0.10, ZGC

> **Interpretation guardrail:** this report measures a **permissive compatibility lane** only.
> It is evidence for isolated boot/startup compatibility under relaxed settings, not
> proof of secure-by-default behavior, multiplayer correctness, hostile-mod safety,
> or general `1.20+` compatibility.

---

## 1. Methodology

For reproducibility, pair future sweep runs with the launcher-generated
`compat-corpus` JSON. That manifest records the exact local JAR hashes,
platform detection results, entrypoints, dependencies, mixin configs, and
unsupported artifacts before any boot result is interpreted.

When harness `results.json` is available, generate `compat-sweep-matrix` from
the corpus and results files. The matrix is the preferred machine-readable
pass/fail/unsupported accounting artifact; this Markdown report remains a human
summary and should not be the only source of truth.

For issue triage, pass the same `results.json` to `diagnostics-bundle` with
`--harness-results` so the zip contains both the raw harness artifact and the
normalized sweep matrix.

Before promoting an alpha artifact, also generate `launch-readiness-report`.
It verifies that required in-repo evidence files and launch-scope docs are
present, but it still relies on the mandatory Gradle gates being run separately.

### 1.1 What is tested

Every mod runs inside a **fresh, isolated Minecraft 1.20.1 dedicated server** process with InterMed attached as a `-javaagent`. The server starts with a flat world, no player connections, and no network access. A test is considered **passing** when the server prints the `Done (Xs)!` banner. A test fails if the process crashes, times out (5 min), or the log contains a fatal exception.

This means a `PASS` in this report should be read narrowly as:
- the mod booted in an isolated dedicated-server process
- under the permissive compatibility settings listed below
- without proving strict capability enforcement, real player interaction, or client/server semantic correctness

```
JVM launch line (Forge example):
  java -Xmx2g -Xms512m -XX:+UseZGC -XX:+ZGenerational
       -javaagent:InterMedCore-8.0-SNAPSHOT.jar      ← InterMed as agent
       --add-opens=java.base/java.lang=ALL-UNNAMED
       @unix_args.txt nogui
```

Fabric tests use `InterMedCore-8.0-SNAPSHOT-fabric.jar` (the Fabric-specific variant of the agent).

### 1.2 Mod selection criteria

Mods are fetched from the **Modrinth v2 API** using three independent sort indices to maximise diversity:

| Sort index | Share | What it captures |
|------------|-------|-----------------|
| `downloads` | 50 % | Most widely used mods (Fabric API, JEI, Create, …) |
| `follows`   | 25 % | Community-starred mods — often larger/heavier mods |
| `updated`   | 25 % | Recently maintained mods — new releases and large mods in active development |

Results are deduplicated by `project_id`, then filtered to **server-compatible** mods only (`server_side ≠ unsupported`). Client-only mods are excluded because they cannot load on a headless server.

### 1.3 InterMed configuration

Each test runs with the following `intermed-runtime.properties` tuned for maximal compatibility rather than strict enforcement:

```properties
aot.cache.enabled=true
mixin.conflict.policy=bridge
mixin.ast.reclaim.enabled=true
security.strict.mode=false
security.legacy.broad.permissions.enabled=true
vfs.enabled=true
vfs.conflict.policy=merge_then_priority
sandbox.espresso.enabled=false
sandbox.wasm.enabled=false
runtime.env=server
resolver.allow.fallback=true
```

This configuration is intentionally different from the project’s strict verification lane
(`./gradlew :app:strictSecurity`). In particular, `security.strict.mode=false` plus
`security.legacy.broad.permissions.enabled=true` makes this report unsuitable as a
security proof.

### 1.4 Outcome taxonomy

| Outcome | Meaning |
|---------|---------|
| `PASS` | Server started cleanly, no warnings |
| `PASS_BRIDGED` | Started successfully; InterMed generated a Mixin priority bridge |
| `PERF_WARN` | Started, but a slow bridge call (>90 s startup or `[PERF]` log marker) was detected |
| `FAIL_CRASH` | Server crashed (non-zero exit, no specific category) |
| `FAIL_MIXIN` | `MixinConflictException` — unresolvable Mixin conflict |
| `FAIL_DEPENDENCY` | `ClassNotFoundException` / `NoClassDefFoundError` for a missing dependency |
| `FAIL_CAPABILITY` | `CapabilityDeniedException` — mod blocked by the security layer in a stricter run or targeted replay |
| `FAIL_TIMEOUT` | Did not start within 5 minutes |
| `FAIL_OTHER` | Port collision, harness error, or other |

---

## 2. Run 1 — Initial Baseline (96 mods, top-100 by downloads)

### 2.1 Summary

| Metric | Value |
|--------|-------|
| Total tests | **96** |
| Passing | **96 (100.0 %)** |
| Failing | 0 |
| Forge tests | 37 (100 % pass) |
| Fabric tests | 59 (100 % pass) |
| Avg startup | **26.3 s** |
| Median startup (p50) | 23.4 s |
| p95 startup | 34.3 s |
| Max startup | 36.8 s |
| Startup σ (stdev) | 5.1 s |

### 2.2 Outcome distribution

```
PASS  ████████████████████████████████████████████  96  (100 %)
```

### 2.3 Startup time distribution

```
Bucket       Count  Bar
──────────── ─────  ──────────────────────────────
< 15 s           0
15 – 25 s       52  ████████████████████████████████████████████████████
25 – 30 s       11  ███████████
30 – 35 s       28  ████████████████████████████
35 – 45 s        5  █████
45 s+            0
```

> 54 % of mods start in under 25 s; 99 % start in under 36 s.
> The 35–45 s tail is exclusively Forge tests (Forge startup overhead ≈ +8 s vs Fabric baseline).

### 2.4 Startup percentile table

| Percentile | Time |
|-----------|------|
| min | 20.4 s |
| p25 | 20.9 s |
| p50 | 23.4 s |
| p75 | 31.8 s |
| p90 | 33.3 s |
| p95 | 34.3 s |
| p99 | 36.8 s |
| max | 36.8 s |
| **avg** | **26.3 s** |

### 2.5 Loader comparison

| Loader | Tests | Pass | Pass rate | Avg startup |
|--------|-------|------|-----------|-------------|
| Forge  | 37 | 37 | **100 %** | 32.1 s |
| Fabric | 59 | 59 | **100 %** | 22.6 s |

Forge takes ~9.5 s longer to start on average (Forge library scanning + ModLauncher overhead).

### 2.6 Top 15 slowest passing mods

| # | Mod | Loader | Startup |
|---|-----|--------|---------|
| 1 | Comforts | Forge | 36.7 s |
| 2 | Cloth Config API | Forge | 36.0 s |
| 3 | YUNG's Better Dungeons | Forge | 35.7 s |
| 4 | M.R.U | Forge | 35.1 s |
| 5 | Better Combat | Forge | 34.3 s |
| 6 | FancyMenu | Forge | 34.3 s |
| 7 | FerriteCore | Forge | 33.9 s |
| 8 | Cristel Lib | Forge | 33.5 s |
| 9 | Balm | Forge | 33.3 s |
| 10 | Just Enough Items (JEI) | Forge | 33.3 s |
| 11 | Quark | Forge | 33.2 s |
| 12 | EMI | Forge | 33.2 s |
| 13 | Shulker Box Tooltip | Forge | 33.1 s |
| 14 | ModernFix | Forge | 33.0 s |
| 15 | Jade 🔍 | Forge | 32.7 s |

### 2.7 Top 10 most-downloaded mods tested

| Mod | Downloads | Outcome |
|-----|-----------|---------|
| Fabric API | 157,129,364 | ✅ PASS |
| Cloth Config API | 112,216,472 | ✅ PASS |
| FerriteCore | 101,883,047 | ✅ PASS |
| Lithium | 85,981,553 | ✅ PASS |
| YetAnotherConfigLib (YACL) | 79,828,298 | ✅ PASS |
| Fabric Language Kotlin | 74,013,242 | ✅ PASS |
| Xaero's Minimap | 73,681,414 | ✅ PASS |
| Architectury API | 71,374,463 | ✅ PASS |
| Xaero's World Map | 65,365,970 | ✅ PASS |
| AppleSkin | 60,969,690 | ✅ PASS |

---

## 3. Run 2 — Extended Suite (52 mods, multi-sort discovery smoke+baseline)

### 3.1 Discovery strategy

The second run fetches mods by three sort indices to cover:
- **Popular mods** (by downloads) — framework APIs, performance mods, UI mods
- **Community-starred mods** (by follows) — heavier content mods: Create, Thermal, Botania, tech mods
- **Recently updated mods** (by updated date) — large mods in active development

### 3.2 Current result snapshot

| Metric | Value |
|--------|-------|
| Total tests | **52** |
| Passing | **52 (100.0 %)** |
| Failing | 0 |
| Forge tests | 21 (100 % pass) |
| Fabric tests | 31 (100 % pass) |
| Avg startup | **27.1 s** |
| Median startup (p50) | 24.1 s |
| p95 startup | 36.4 s |
| Max startup | 37.3 s |
| Startup σ (stdev) | 5.4 s |

This run is the first extended baseline after stabilising two harness-level issues:
- **Fabric bootstrap isolation** — the Fabric-specific agent variant now defers deep bootstrap and avoids early classpath pollution from embedded Mixin/ASM runtime
- **Parallel run port allocation** — the harness now assigns truly unique server ports per run instead of a hash-derived port, eliminating false negatives from bind collisions

### 3.3 Loader comparison

| Loader | Tests | Pass | Pass rate | Avg startup |
|--------|-------|------|-----------|-------------|
| Forge  | 21 | 21 | **100 %** | 33.0 s |
| Fabric | 31 | 31 | **100 %** | 23.1 s |

### 3.4 Startup percentile table

| Percentile | Time |
|-----------|------|
| min | 19.7 s |
| p50 | 24.1 s |
| p75 | 32.1 s |
| p90 | 34.8 s |
| p95 | 36.4 s |
| p99 | 37.3 s |
| max | 37.3 s |
| **avg** | **27.1 s** |

### 3.5 Notes

- No failing mod-specific signatures remain in the current top-50 baseline.
- The only previously observed tail failure (`resourceful-lib` on Fabric) was traced to a harness port collision and disappeared after switching to a true unique-port allocator.
- Forge still shows a consistent startup overhead of roughly +10 s relative to Fabric in isolated dedicated-server runs.

---

## 4. Run 3 — Top-100 Extended Baseline (103 test cases)

### 4.1 Summary

| Metric | Value |
|--------|-------|
| Total tests | **103** |
| Passing | **103 (100.0 %)** |
| Failing | 0 |
| Forge tests | 40 (100 % pass) |
| Fabric tests | 63 (100 % pass) |
| Avg startup | **27.6 s** |
| Median startup (p50) | 25.2 s |
| p95 startup | 36.0 s |
| Max startup | 38.3 s |
| Startup σ (stdev) | 5.3 s |

### 4.2 Loader comparison

| Loader | Tests | Pass | Pass rate | Avg startup |
|--------|-------|------|-----------|-------------|
| Forge  | 40 | 40 | **100 %** | 33.8 s |
| Fabric | 63 | 63 | **100 %** | 23.6 s |

Forge again shows a repeatable +10.2 s startup overhead versus Fabric. This strongly suggests the remaining startup delta is dominated by loader/runtime cost rather than InterMed itself.

### 4.3 Startup percentile table

| Percentile | Time |
|-----------|------|
| min | 20.7 s |
| p50 | 25.2 s |
| p75 | 33.4 s |
| p90 | 35.2 s |
| p95 | 36.0 s |
| p99 | 37.1 s |
| max | 38.3 s |
| **avg** | **27.6 s** |

### 4.4 Notable popular mods verified in the baseline

These are the kinds of names that matter when presenting compatibility externally: widely downloaded framework mods, performance mods, worldgen/content mods, and heavy scripting/runtime integrations.

| Mod | Loader | Downloads | Outcome |
|-----|--------|-----------|---------|
| Fabric API | Fabric | 156.8M | ✅ PASS |
| Cloth Config API | Forge | 112.0M | ✅ PASS |
| FerriteCore | Forge | 101.7M | ✅ PASS |
| Lithium | Fabric | 85.8M | ✅ PASS |
| YetAnotherConfigLib (YACL) | Forge | 79.7M | ✅ PASS |
| Fabric Language Kotlin | Fabric | 73.9M | ✅ PASS |
| Xaero's Minimap | Fabric | 73.6M | ✅ PASS |
| Architectury API | Forge | 71.3M | ✅ PASS |
| Xaero's World Map | Fabric | 65.3M | ✅ PASS |
| AppleSkin | Fabric | 60.9M | ✅ PASS |
| ModernFix | Forge | 55.9M | ✅ PASS |
| Just Enough Items (JEI) | Forge | 47.6M | ✅ PASS |
| Jade 🔍 | Forge | 47.5M | ✅ PASS |
| Geckolib | Forge | 45.2M | ✅ PASS |
| Simple Voice Chat | Fabric | 43.5M | ✅ PASS |

### 4.5 Notable larger / heavier mods verified

| Mod | Loader | Why it matters | Outcome |
|-----|--------|----------------|---------|
| Create | Forge | Large content/kinetics mod with broad integration surface | ✅ PASS |
| Cobblemon | Fabric | Large gameplay/content mod with substantial runtime surface | ✅ PASS |
| Distant Horizons | Fabric + Forge | Heavy render/distance stack; useful stress signal even in server baseline | ✅ PASS |
| Quark | Forge | Large kitchen-sink content/system mod | ✅ PASS |
| KubeJS | Forge | Scripting/runtime-heavy mod, good classloading signal | ✅ PASS |
| Traveler's Backpack | Forge | Content + capability/storage-heavy mod | ✅ PASS |
| Terralith | Fabric | Large worldgen datapack/mod-style content surface | ✅ PASS |
| Farmer's Delight | Forge | Popular gameplay/content mod with recipe/data footprint | ✅ PASS |

### 4.6 Slowest passing mods (top 15)

The slow tail remains almost entirely Forge-heavy, which is consistent with ModLauncher + Forge bootstrap overhead rather than a compatibility problem.

| # | Mod | Loader | Startup |
|---|-----|--------|---------|
| 1 | Timeless and Classics Zero | Forge | 38.3 s |
| 2 | Resourceful Config | Forge | 37.1 s |
| 3 | Polymorph | Forge | 37.1 s |
| 4 | Traveler's Backpack | Forge | 36.6 s |
| 5 | Waystones | Forge | 36.5 s |
| 6 | Quark | Forge | 36.0 s |
| 7 | ModernFix | Forge | 35.8 s |
| 8 | Rhino | Forge | 35.6 s |
| 9 | FancyMenu | Forge | 35.5 s |
| 10 | Forgified Fabric API | Forge | 35.3 s |
| 11 | Kotlin for Forge | Forge | 35.2 s |
| 12 | Comforts | Forge | 34.9 s |
| 13 | KubeJS | Forge | 34.7 s |
| 14 | Sinytra Connector | Forge | 34.6 s |
| 15 | CreativeCore | Forge | 34.3 s |

---

## 5. Pair Compatibility Baseline (top-20 pair matrix)

After the single-mod baseline reached a repeatable `103/103 PASS`, InterMed was exercised against a pairwise matrix built from the top-20 mod subset. This checks whether common “foundation mods” still boot cleanly when combined, which is the first practical step toward validating real modpack scenarios.

### 5.1 Summary

| Metric | Value |
|--------|-------|
| Total tests | **184** |
| Passing | **184 (100.0 %)** |
| Failing | 0 |
| Single-mod anchor cases | 103 |
| Pair cases | 81 |
| Pair avg startup | **27.2 s** |
| Pair median startup (p50) | 24.1 s |
| Pair p95 startup | 37.4 s |
| Pair max startup | 38.9 s |

### 5.2 What this matrix covered

The pair matrix included representative combinations of:
- **Fabric baseline stack**: `fabric-api`, `lithium`, `fabric-language-kotlin`, `xaeros-minimap`, `xaeros-world-map`, `appleskin`, `collective`, `forge-config-api-port`, `konkrete`, `puzzles-lib`
- **Forge baseline stack**: `cloth-config`, `ferrite-core`, `yacl`, `architectury-api`, `modernfix`, `jei`, `jade`, `collective`, `geckolib`

This is useful because these mods frequently act as dependency hubs, UI/config layers, or performance foundations for larger packs.

### 5.3 Notable verified pair combinations

| Pair | Loader | Outcome |
|------|--------|---------|
| Fabric API + Lithium | Fabric | ✅ PASS |
| Fabric API + Fabric Language Kotlin | Fabric | ✅ PASS |
| Fabric API + Xaero's Minimap | Fabric | ✅ PASS |
| Fabric API + Xaero's World Map | Fabric | ✅ PASS |
| Fabric API + Forge Config API Port | Fabric | ✅ PASS |
| Lithium + Xaero's Minimap | Fabric | ✅ PASS |
| Lithium + Xaero's World Map | Fabric | ✅ PASS |
| Lithium + Puzzles Lib | Fabric | ✅ PASS |
| Xaero's Minimap + Xaero's World Map | Fabric | ✅ PASS |
| Cloth Config API + FerriteCore | Forge | ✅ PASS |
| Cloth Config API + YACL | Forge | ✅ PASS |
| Cloth Config API + Architectury API | Forge | ✅ PASS |
| Cloth Config API + ModernFix | Forge | ✅ PASS |
| Cloth Config API + JEI | Forge | ✅ PASS |
| Cloth Config API + Jade | Forge | ✅ PASS |
| Architectury API + ModernFix | Forge | ✅ PASS |
| JEI + Jade | Forge | ✅ PASS |
| JEI + Geckolib | Forge | ✅ PASS |

### 5.4 Slowest verified pairs

The slowest pair starts are still overwhelmingly Forge-oriented, which mirrors the single-mod trend and points to loader/runtime overhead rather than incompatibility.

| # | Pair | Loader | Startup |
|---|------|--------|---------|
| 1 | Cloth Config API + YACL | Forge | 38.9 s |
| 2 | Cloth Config API + FerriteCore | Forge | 38.2 s |
| 3 | Cloth Config API + ModernFix | Forge | 37.9 s |
| 4 | Cloth Config API + Architectury API | Forge | 37.5 s |
| 5 | YACL + Jade | Forge | 37.4 s |
| 6 | YACL + Architectury API | Forge | 36.3 s |
| 7 | YACL + JEI | Forge | 36.1 s |
| 8 | JEI + Geckolib | Forge | 35.7 s |
| 9 | Jade + Collective | Forge | 35.7 s |
| 10 | Cloth Config API + JEI | Forge | 35.5 s |

### 5.5 Interpretation

- No pair in the current top-20 matrix produced a startup crash, timeout, dependency failure, or Mixin conflict.
- The current matrix is still conservative: it validates **boot compatibility**, not deep gameplay semantics after hours of play.
- The next meaningful escalation is a wider pair matrix (`top-30` or `top-40`) and then curated “heavy pack slices” that include larger content mods together.

---

## 6. Observations and Analysis

### 6.1 Mixin bridge behaviour

InterMed's Mixin AST bridge (`mixin.conflict.policy=bridge`) was not triggered in the baseline run. This indicates that the top-100 most popular mods do not produce Mixin conflicts with each other when loaded **individually** (single-mod test mode). Conflicts typically arise in **multi-mod scenarios** and are exercised in the `--mode=pairs` and `--mode=full` test plans.

### 6.2 Forge vs Fabric startup overhead

Forge 47.3.0 adds a consistent ~9.5 s overhead relative to Fabric tests. This is attributable to:
- Forge's `ModLauncher` + `SecureJarHandler` classpath scanning
- Forge's own Mixin pipeline (`mixinextras`) initialisation
- More verbose logging (Forge logs ~3× more lines than Fabric on startup)

Within this isolated permissive harness, InterMed's observed overhead on top of both loaders is negligible (< 0.3 s in all observed cases). This is not yet a native-baseline proof for external modpacks.

### 6.3 AOT cache warm-up

First-run tests show AOT cache `MISS` events as the JIT profiles are built. Subsequent runs of the same mod (e.g., via `--skip-run` + report regeneration) would show `HIT` entries, reducing startup time by an estimated 15–25 %.

For the frozen in-repo scope, end-to-end warm-cache behavior is now also covered separately by the `WarmCacheStartupEvidenceTest`; this report remains a harness-level compatibility snapshot rather than the canonical startup proof.

### 6.4 VFS conflict resolution

No VFS resource conflicts were detected in single-mod tests (expected). The `vfs.conflict.policy=merge_then_priority` policy is exercised primarily when multiple mods ship overlapping resource paths — this is tested in pair and pack scenarios.

### 6.5 Security layer

`security.strict.mode=false` and `security.legacy.broad.permissions.enabled=true` are set for maximal compatibility during testing. In strict launch profiles, mods attempting broad file-system or reflection access will trigger `CapabilityDeniedException`.

This compatibility report must therefore not be cited as evidence that a modpack is safe under the default security posture. Security claims for the frozen scope are tracked separately through the strict fail-closed verification lane (`./gradlew :app:strictSecurity`).

---

## 7. How to reproduce

### 7.1 Full run (all steps)

```bash
# One-time setup (downloads MC + Forge/Fabric, ~500 MB)
./test-harness/run.sh bootstrap

# Discover and download mods (~200 JARs)
./test-harness/run.sh discover --top=200

# Run all tests (concurrency=4, ~45 min)
./test-harness/run.sh run --top=200 --concurrency=4

# Generate HTML + JSON report
./test-harness/run.sh report
```

If you need strict security verification as well, run it separately from the harness sweep:

```bash
./gradlew :app:strictSecurity --stacktrace --no-daemon
```

### 7.2 Quick re-run (skip already-done steps)

```bash
# Skip bootstrap + discovery, only run tests
./test-harness/run.sh run --top=200 --concurrency=4 --skip-discover

# Skip everything, just regenerate the report from cached results
./test-harness/run.sh report --skip-discover
```

### 7.3 Loader-specific runs

```bash
# Forge only
./test-harness/run.sh full --loader=forge --top=100 --concurrency=4

# Fabric only
./test-harness/run.sh full --loader=fabric --top=100 --concurrency=8
```

### 7.4 Pair compatibility matrix

```bash
# Test top-50 mods in all two-mod combinations (1225 pairs)
./test-harness/run.sh full --mode=pairs --top=200 --pairs-top=50 --concurrency=4
```

---

## 8. Report artefacts

| File | Description |
|------|-------------|
| `test-harness/harness-output/report/index.html` | Self-contained HTML dashboard with charts, tables, CSV export |
| `test-harness/harness-output/report/results.json` | Machine-readable full result set (all test cases, issues, startup times) |
| `test-harness/harness-output/cache/mods/registry.json` | Downloaded mod registry (Modrinth metadata) |
| `test-harness/harness-output/runs/<id>/logs/harness.log` | Raw server log per test case |

The HTML report includes:

- **KPI bar** — 8 metric cards (total, pass, bridged, perf-warn, fail counts; pass rate; avg and p95 startup)
- **Outcome donut chart** — visual breakdown of pass/fail categories
- **Startup histogram** — 7 time buckets (< 15 s … 120 s+)
- **Failure breakdown** — bar chart per fail category
- **Percentile table** — min / p50 / p75 / p90 / p95 / p99 / max / avg
- **Loader comparison** — Forge vs Fabric side-by-side
- **Top issue tags** — most frequent non-INFO log tags across all tests
- **Slowest 15 mods** — startup time ranking
- **Full results table** — filterable by outcome / loader / search, sortable by any column, expandable log snippets, CSV export
