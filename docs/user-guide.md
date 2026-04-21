# InterMed v8.0.0-alpha.1 Open Alpha User Guide

InterMed `v8.0.0-alpha.1` is an experimental JVM hypervisor for Minecraft `1.20.1` that is being hardened for mixed Fabric, Forge, and NeoForge compatibility. This guide describes the current alpha path, not a production-ready compatibility guarantee.

---

## Table of Contents

1. [System requirements](#1-system-requirements)
2. [Installation](#2-installation)
3. [Adding mods](#3-adding-mods)
4. [First launch](#4-first-launch)
5. [Conflict resolution FAQ](#5-conflict-resolution-faq)
6. [Security policy configuration](#6-security-policy-configuration)
7. [Performance tuning](#7-performance-tuning)
8. [Diagnostics and logs](#8-diagnostics-and-logs)
9. [Troubleshooting](#9-troubleshooting)
10. [Reporting alpha issues](#10-reporting-alpha-issues)

---

## 1. System requirements

| Component         | Minimum                    | Recommended                   |
|-------------------|----------------------------|-------------------------------|
| JDK               | 21 (LTS)                   | 22 or 23 (FFM off-heap gains) |
| RAM (heap)        | 4 GB (`-Xmx4G`)            | 8 GB or more                  |
| RAM (off-heap)    | 512 MB free native memory  | 1 GB                          |
| Minecraft         | 1.20.1                     | 1.20.1                         |
| OS                | Linux, macOS, Windows 10+  | Linux (best JIT behaviour)    |

InterMed does not support Java 8 or Java 11. The GraalVM Espresso sandbox requires GraalVM JDK 21+ when `sandbox.espresso.enabled = true`.

---

## 2. Installation

### 2.1 Unified launch kit (recommended)

1. Use the packaged alpha build or build the local launcher/core JARs from this repository:

   ```bash
   ./gradlew :app:coreJar :app:coreFabricJar :app:bootstrapJar
   ```

2. Generate a local launch kit once for the target game directory:

   ```bash
   java -jar app/build/libs/InterMedCore-8.0.0-alpha.1.jar launch-kit \
     --game-dir /path/to/.minecraft
   ```

   This creates `.intermed/launch-kit/` inside the game directory with:
   - launcher-agnostic JVM args snippets
   - Linux/macOS wrapper scripts
   - Windows `.cmd` wrapper scripts
   - a staged `runtime/` folder containing the exact InterMed JARs that the kit references

3. Use the generated files in the way that best matches your setup:

   **Any launcher with a JVM args field**
   - open `launcher-jvm-args-generic.txt`
   - for Fabric launcher profiles, use `launcher-jvm-args-fabric.txt`
   - paste the single line into the launcher's JVM arguments / Java flags field

   **Linux/macOS direct server launch**
   ```bash
   ./.intermed/launch-kit/intermed-launch-generic.sh -jar minecraft_server.jar nogui
   ```

   **Windows direct server launch**
   ```bat
   .\.intermed\launch-kit\intermed-launch-generic.cmd -jar minecraft_server.jar nogui
   ```

   Use the `fabric` wrapper variants for Fabric-specific launch profiles.

4. On first launch InterMed creates:
   - `intermed_mods/` — put alpha-test mods here (Fabric, Forge, and NeoForge JARs can coexist within the current compatibility envelope)
   - `config/intermed-runtime.properties` — main configuration file (see [Section 7](../docs/config-reference.md))
   - `.intermed/` — internal caches (do not edit manually)

### 2.2 Manual fallback

If you prefer to wire the process yourself, keep the same runtime model as the launch kit:

- `-javaagent:/path/to/InterMedCore-8.0.0-alpha.1.jar`
- `--add-opens=java.base/java.lang=ALL-UNNAMED`
- `--add-opens=java.base/sun.nio.ch=ALL-UNNAMED`
- `-Druntime.game.dir=/path/to/gameDir`
- `-Druntime.mods.dir=/path/to/gameDir/intermed_mods`

For Fabric launcher profiles, swap the agent JAR for `InterMedCore-8.0.0-alpha.1-fabric.jar`.

### 2.3 Gradle / Maven integration (for server operators)

Add the InterMed artifact to your build:

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.intermed:intermed-runtime:8.0.0-alpha.1")
}
```

Then configure the main class in your launch script to `org.intermed.cli.InterMedCLI`.

### 2.4 Upgrading from an earlier InterMed version

The AOT bytecode cache is versioned (`aot_v8`). When upgrading from v7 or earlier, delete `.intermed/aot_v8/` to force a full cache rebuild on next startup. This is done automatically if the cache format version does not match; the deletion message appears in the log as:

```
[AOT] Cache format mismatch (expected 4, found 3) — invalidating all cached entries
```

---

## 3. Adding mods

Place mod JARs directly into `intermed_mods/`. InterMed detects the loader type from the manifest:

| Manifest attribute / file       | Detected as    |
|---------------------------------|----------------|
| `fabric.mod.json` present       | Fabric mod     |
| `META-INF/mods.toml` present    | Forge mod      |
| `META-INF/neoforge.mods.toml`   | NeoForge mod   |
| `intermed.json` present         | Native InterMed|

Mods of different loader families can be placed in the same `intermed_mods/` directory for alpha testing. This is a compatibility target, not a guarantee that every public mod combination will boot or behave correctly yet.

### 3.1 Dependency JARs

Library JARs that are not themselves mods (e.g. `kotlin-stdlib.jar`, `guava.jar`) are also placed in `intermed_mods/`. InterMed detects them via the absence of a mod descriptor and routes them through the Welsh–Powell library clustering pass, so duplicate libraries from different mods are automatically deduplicated.

### 3.2 Mod priority

When multiple mods conflict on the same resource or Mixin, the one with the higher priority value wins. Default priority is 1000. Override it in `intermed-runtime.properties`:

```properties
mod.priority.my-cool-mod=2000
mod.priority.other-mod=500
```

Or declare priority in `intermed.json`:

```json
{
  "modId": "my-cool-mod",
  "intermedPriority": 2000
}
```

---

## 4. First launch

On first launch InterMed performs:

1. **Dependency resolution** — PubGrub SAT solver resolves the full dependency graph. Any unresolvable conflict is printed to the log with a detailed explanation.
2. **ClassLoader DAG construction** — each mod is assigned an isolated ClassLoader node.
3. **VFS scan** — all `assets/` and `data/` entries are indexed; conflicts are resolved and written to `.intermed/vfs/diagnostics.json`.
4. **Mixin transformation** — all Mixin classes are resolved and conflicting Mixins are bridged. Conflict details are logged at `INFO` level.
5. **AOT cache warm-up** — already-resolved classes are written to `.intermed/aot_v8/bytecode/`. Subsequent launches skip this work for unchanged mods.

In current in-repo and harness-style tests, warm-cache launches are significantly faster than cold launches. Real modpacks may vary until native-baseline and field-scale startup evidence is collected.

---

## 5. Conflict resolution FAQ

### Q: I see `[AST] Generated priority bridge for … mods=[mod-a, mod-b]` — is this a problem?

No. A priority bridge means two mods both modify the same method and their changes could not be merged automatically. InterMed generates a synthetic method that calls both mods in priority order. The game continues to run. Only review this if you observe unexpected gameplay behaviour.

### Q: The log says `MixinConflictException` and the game did not start.

`mixin.conflict.policy = fail` is set. This is intended for CI environments. Change it to `bridge` (the default) for normal play, or resolve the underlying Mixin conflict by adjusting mod priorities.

### Q: Two mods register the same block ID and one is being silently dropped.

Check `.intermed/vfs/diagnostics.json`. The `suggestedOverride` field lists the exact override rule to add to `intermed-vfs-overrides.json` to control which mod wins. See the [VFS override rules](#vfs-override-rules-file) section in the dev guide.

### Q: A Fabric mod crashes with `ClassNotFoundException: net.minecraftforge.…`

The mod has a hard dependency on Forge classes without declaring it. Enable `resolver.allow.fallback = true` in `intermed-runtime.properties` to allow ClassLoader fallback to the Forge layer. If the class is not present at all, the mod is incompatible and must be excluded.

### Q: A Forge mod crashes with `ClassNotFoundException: net.fabricmc.…`

Same as above but in reverse. Set `resolver.allow.fallback = true`. If the mod calls `FabricLoader.getInstance()` it will receive InterMed's bridge implementation (`FabricLoaderBridge`), which satisfies the most common uses.

### Q: I have two texture packs for the same block and the wrong one is showing.

Add a priority override to `config/intermed-vfs-overrides.json`:

```json
{
  "rules": [
    {
      "pattern": "assets/minecraft/textures/block/stone.png",
      "policy": "priority_override",
      "winnerModId": "my-preferred-texture-mod"
    }
  ]
}
```

Then restart the game; the VFS cache refreshes automatically.

### Q: Startup is slow even after the first launch.

Check whether the AOT cache was invalidated. Look for `[AOT] Cache MISS` lines in the log. Common causes:
- A mod JAR was updated (the cache is SHA-256 keyed per class).
- The InterMed platform version changed (component fingerprints changed).
- The JVM version changed (the cache is JVM-agnostic but bytecode changes on toolchain update).

To force a full rebuild: delete `.intermed/aot_v8/`.

---

## 6. Security policy configuration

InterMed enforces a capability-based security model. Each mod must be granted capabilities it needs; otherwise operations are blocked (in strict mode) or logged as permissive warnings (in permissive mode).

Compatibility reports and harness sweeps published by the project use a separate
permissive lane for broad boot coverage. They are useful for compatibility triage,
but they are not proof that your own installation is running with secure defaults.

### 6.1 Strict mode (default)

```properties
security.strict.mode=true
```

Any mod that tries to open a socket, write a file, spawn a process, load native code, use private reflection, or access Unsafe/VarHandle/FFM without an explicit grant gets `CapabilityDeniedException`. The exception message includes the mod id, requested capability, scoped path/host/member when available, the denial reason, and the safest next action. Sensitive operations that cannot be attributed to a known mod or trusted host/platform stack are also denied in strict mode. Denials are written to `logs/intermed-security.log`.

### 6.2 Security profiles

For alpha testing, prefer the external profile file:

```text
config/intermed-security-profiles.json
```

Example for an ordinary config-reading mod:

```json
[
  {
    "modId": "ordinary-mod",
    "capabilities": ["FILE_READ"],
    "fileReadPaths": ["config/ordinary-mod/**"],
    "fileWritePaths": [],
    "networkHosts": [],
    "unsafeMembers": []
  }
]
```

Example for a network/file/config-heavy mod:

```json
[
  {
    "modId": "network-file-config-heavy-mod",
    "capabilities": ["FILE_READ", "FILE_WRITE", "NETWORK_CONNECT"],
    "fileReadPaths": [
      "config/network-file-config-heavy-mod/**",
      "resourcepacks/network-file-config-heavy-mod/**"
    ],
    "fileWritePaths": [
      "config/network-file-config-heavy-mod/**",
      ".intermed/cache/network-file-config-heavy-mod/**"
    ],
    "networkHosts": ["api.example.org", "*.cdn.example.org"],
    "unsafeMembers": []
  }
]
```

The same examples live under `examples/security-profiles/` in the repository.

Manifest-side declarations are also supported for mod authors:

```json
{
  "modId": "my-mod",
  "capabilityGrants": ["FILE_READ", "NETWORK_CONNECT"]
}
```

### 6.3 Legacy broad permissions

If you have many old mods that predate capability declarations and you trust them all:

```properties
security.legacy.broad.permissions.enabled=true
```

This grants all capabilities to every mod that does not have an explicit grant list. **Use only in private/trusted environments.** Mods that use `PROCESS_SPAWN` or `NATIVE_LIBRARY` can escape the JVM sandbox.

### 6.4 Available capabilities

| Capability         | What it covers                             |
|--------------------|--------------------------------------------|
| `FILE_READ`        | Reading files from disk                    |
| `FILE_WRITE`       | Writing or deleting files                  |
| `NETWORK_CONNECT`  | Opening network connections                |
| `MEMORY_ACCESS`    | Direct memory segment access               |
| `UNSAFE_ACCESS`    | `sun.misc.Unsafe` low-level operations     |
| `REFLECTION_ACCESS`| Bypassing module visibility (`setAccessible`)|
| `PROCESS_SPAWN`    | Launching OS processes                     |
| `NATIVE_LIBRARY`   | Loading native `.so` / `.dll` libraries    |

### 6.5 Disabling strict mode for troubleshooting

```properties
security.strict.mode=false
```

With this setting, capability violations are logged but never throw. Check `logs/intermed-security.log` to see what a mod is attempting; then add the required grants and re-enable strict mode.

Use this only as a temporary troubleshooting step. A successful boot in permissive mode
does not mean the mod is acceptable under the default secure posture. Project harness
compatibility sweeps that use permissive settings are never described as secure runs.

---

## 7. Performance tuning

### 7.1 AOT cache

Enabled by default. To disable (useful during development to always recompile):

```properties
aot.cache.enabled=false
```

### 7.2 AST metadata reclamation

After Mixin resolution, intermediate AST metadata is reclaimed from the heap by default. To keep it for post-startup diagnostics:

```properties
mixin.ast.reclaim.enabled=false
```

Disabling this increases heap usage by approximately 50–200 MB for a large modpack.

### 7.3 Ring buffer capacity

The event bus ring buffer size. Increase if you observe event loss under heavy load:

```
# JVM system property (not in .properties file)
-Dintermed.events.ring-buffer.capacity=4096
```

Default is 1024. Must be a power of two.

### 7.4 Sandbox region pool

For servers with many polyglot mods, increase the shared memory pool:

```properties
sandbox.shared.region.bytes=16384
sandbox.shared.region.pool.max=64
```

`sandbox.shared.region.bytes` is clamped to a minimum of 1024 bytes.

### 7.5 JVM flags

Recommended JVM flags for controlled alpha/server testing:

```
-server
-XX:+UseZGC
-XX:+ZGenerational
-Xmx8G
-Xms2G
-XX:+AlwaysPreTouch
-XX:+DisableExplicitGC
--enable-preview
--add-opens java.base/java.lang=ALL-UNNAMED
--add-opens java.base/sun.nio.ch=ALL-UNNAMED
-javaagent:/path/to/InterMedCore-8.0.0-alpha.1.jar
```

If you generated a launch kit, those InterMed-specific flags are already included
in `intermed-java-generic.args` / `intermed-java-fabric.args`, so you only need to
add your own heap/GC tuning on top.

ZGC with generational mode (`-XX:+ZGenerational`, available since JDK 21) significantly reduces pause times compared to G1GC under InterMed's heavy allocation pattern during startup.

### 7.6 Alpha performance evidence

Open-alpha artifacts include `app/build/reports/performance/alpha-performance-snapshot.json`
and `app/build/reports/performance/native-loader-baseline.json`. This is an
initial alpha performance snapshot, not a final overhead claim. It records clean
Fabric, clean Forge, and InterMed-attached short dedicated-server lanes, and it
keeps the current registry/remapper/event-bus microbench reports labeled as
internal hot-path evidence.

Generate or refresh the lightweight baseline with:

```bash
./test-harness/run.sh performance-baseline --heap=768 --timeout=180
```

Do not cite InterMed as meeting the `10-15%` overhead target until longer
native-loader and InterMed modpack baselines are captured under comparable
settings.

---

## 8. Diagnostics and logs

### 8.1 Log files

| File                                | Contents                                                    |
|-------------------------------------|-------------------------------------------------------------|
| `logs/latest.log`                   | Main game log including InterMed startup messages           |
| `logs/intermed-security.log`        | Capability check results (all verdicts in strict mode)      |
| `.intermed/vfs/diagnostics.json`    | VFS conflict resolution report with suggested overrides     |
| `logs/intermed-metrics.json`        | OTel traces (if `metrics.otel.enabled = true`)              |

### 8.2 Diagnostics bundle

For alpha triage, generate a single zip that can be attached to an issue or CI
artifact:

```bash
java -cp InterMedCore.jar org.intermed.launcher.InterMedLauncher diagnostics-bundle \
  --game-dir /path/to/game \
  --mods-dir /path/to/game/intermed_mods \
  --harness-results /path/to/harness-output/report/results.json \
  --output /path/to/intermed-diagnostics-bundle.zip
```

The bundle contains `manifest.json`, a launch-readiness report, compatibility
report, compatibility corpus manifest, compatibility sweep matrix, SBOM, API gap
matrix, dependency plan, security snapshot, runtime config snapshot, selected
logs, VFS diagnostics, and available runtime evidence reports. It is a triage artifact, not a
production stability or security guarantee.

If `--harness-results` is provided, the bundle stores that file as
`artifacts/harness/results.json` and uses it to build
`reports/compatibility-sweep-matrix.json`. Without it, the matrix remains a
`corpus-only-not-run` snapshot.

To generate only the current launch-readiness summary:

```bash
java -cp InterMedCore.jar org.intermed.launcher.InterMedLauncher launch-readiness-report \
  --project-root /path/to/InterMed \
  --game-dir /path/to/game \
  --mods-dir /path/to/game/intermed_mods \
  --harness-results /path/to/harness-output/report/results.json \
  --output /path/to/intermed-launch-readiness-report.json
```

This report checks evidence artifact presence and documentation guardrails. It
does not run Gradle gates or prove field readiness.

To generate only the current local corpus manifest:

```bash
java -cp InterMedCore.jar org.intermed.launcher.InterMedLauncher compat-corpus \
  --mods-dir /path/to/game/intermed_mods \
  --output /path/to/intermed-compatibility-corpus.json
```

To link a corpus manifest with a stored harness `results.json`:

```bash
java -cp InterMedCore.jar org.intermed.launcher.InterMedLauncher compat-sweep-matrix \
  --corpus /path/to/intermed-compatibility-corpus.json \
  --results /path/to/harness-output/report/results.json \
  --output /path/to/intermed-compatibility-sweep-matrix.json
```

To generate only the current curated API surface matrix:

```bash
java -cp InterMedCore.jar org.intermed.launcher.InterMedLauncher api-gap-matrix \
  --output /path/to/intermed-api-gap-matrix.json
```

When using `InterMedLauncher launch`, this bundle is written automatically if the
launched game/server process exits with a non-zero code. By default it is placed
under `.intermed/diagnostics/` in the game directory. Use
`--diagnostics-output /path/to/bundle.zip` to choose an exact path, or
`--no-diagnostics-bundle` to disable automatic bundle creation for a specific run.

### 8.3 Verbose flags

| Flag                                          | What it enables                                       |
|-----------------------------------------------|-------------------------------------------------------|
| `-Dintermed.classloader.verbose=true`         | Full ClassLoader delegation chain per class load      |
| `-Dintermed.mixin.conflict.report=true`       | Full Mixin conflict reports at startup                |
| `-Dintermed.security.verbose=true`            | Every capability check decision (very verbose)        |
| `-Dintermed.aot.verbose=true`                 | Cache hit/miss per class                              |
| `-Dintermed.vfs.verbose=true`                 | Per-resource resolution steps                         |

### 8.4 Runtime reload

You can reload `intermed-runtime.properties` without restarting (config changes only — no mod additions):

**Server:** Run the `/intermed reload` command in the server console.

**Client:** Press `F3+Shift+I` (default keybind, configurable).

This calls `RuntimeConfig.reload()` which invalidates the capability decision cache, sandbox runtime caches, and VFS cache.

---

## 9. Troubleshooting

### Game freezes at "Resolving mod dependencies"

The PubGrub solver is working through a complex conflict graph. This normally completes in under 30 seconds. If it runs longer, check the log for `[RESOLVER] Backtrack depth …` messages. A depth above 50 usually indicates a genuinely unresolvable dependency cycle — remove one of the conflicting mods and restart.

### `OutOfMemoryError: Java heap space` during startup

Increase `-Xmx`. With large modpacks (100+ mods) and Mixin AST reclamation disabled, the startup peak can reach 6 GB. Set `mixin.ast.reclaim.enabled=true` (the default) to release AST data after transformation.

### `UnsatisfiedLinkError` on native mods

Mods that bundle native libraries require `NATIVE_LIBRARY` capability. Grant it and set the library path:

```properties
mod.grant.native-mod=NATIVE_LIBRARY
```

If the native library is incompatible with your OS/arch, it must be excluded from `intermed_mods/`.

### A mod works on Fabric standalone but not under InterMed

1. Check `logs/intermed-security.log` for capability violations.
2. Check for `[AST]` warnings about Mixin conflicts — the mod may have a Mixin that conflicts with another.
3. Check `.intermed/vfs/diagnostics.json` for overridden resources the mod depends on.
4. If the mod uses `FabricLoader.getInstance()` and expects the real Fabric loader, generate `api-gap-matrix` and check whether the method it calls is present, missing, or outside the curated alpha matrix (file a GitHub issue if not).

### A mod works on Forge standalone but not under InterMed

Same checklist as above. Additionally verify that the mod does not depend on Forge's `@SubscribeEvent` static event bus registration; use `ForgeEventBridge.register(handler)` instead, which translates to InterMed's event system.

### Crash with `CapabilityDeniedException: REFLECTION_ACCESS`

A mod is calling `Field.setAccessible(true)` or `Module.addOpens()` dynamically. Grant `REFLECTION_ACCESS`:

```properties
mod.grant.my-mod=REFLECTION_ACCESS
```

If you do not trust the mod's use of reflection, leave it blocked — the mod may be trying to bypass security intentionally.

---

## 10. Reporting alpha issues

Before filing an issue:

1. Check [known-limitations.md](known-limitations.md) to see whether the behavior
   is already outside the current alpha claim.
2. Generate or locate a diagnostics bundle. `InterMedLauncher launch` writes one
   automatically on non-zero process exit.
3. Follow [alpha-triage.md](alpha-triage.md) and choose the closest GitHub issue
   template: crash/launch failure, compatibility gap, performance regression, or
   security hardening.

Do not upload logs containing tokens, session IDs, private server addresses, or
API keys. For sensitive security issues, follow `.github/SECURITY.md` instead of
opening a public issue.
