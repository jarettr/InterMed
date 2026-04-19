# InterMed v8.0 Alpha Snapshot — Configuration Reference

All active alpha settings live in `intermed-runtime.properties`, loaded from the classpath root (inside the InterMed JAR) and overridable by a file placed in `<gameDir>/config/intermed-runtime.properties`. Every key can also be set as a JVM system property (`-Dkey=value`), which takes highest precedence. TOML/YAML configuration and GUI override flows are deferred launch criteria, not active alpha surfaces.

**Lookup order:** JVM system property → `config/intermed-runtime.properties` → built-in defaults.

---

## Table of Contents

1. [AOT cache](#1-aot-cache)
2. [Mixin conflict resolution](#2-mixin-conflict-resolution)
3. [Security and capabilities](#3-security-and-capabilities)
4. [ClassLoader DAG](#4-classloader-dag)
5. [Dependency resolver](#5-dependency-resolver)
6. [Sandbox / polyglot runtime](#6-sandbox--polyglot-runtime)
7. [Virtual File System (VFS)](#7-virtual-file-system-vfs)
8. [Observability](#8-observability)
9. [Runtime paths and environment](#9-runtime-paths-and-environment)
10. [Event bus (JVM system properties)](#10-event-bus-jvm-system-properties)
11. [Mod-level overrides](#11-mod-level-overrides)
12. [Sample configuration files](#12-sample-configuration-files)

---

## 1. AOT cache

### `aot.cache.enabled`

| Type    | Default | Overridable via `-D` |
|---------|---------|----------------------|
| boolean | `true`  | yes                  |

Controls whether the Ahead-of-Time bytecode cache is active. When enabled, resolved and transformed class bytecode is written to `.intermed/aot_v8/bytecode/` and reused on subsequent launches. The cache is keyed by a SHA-256 hash of all inputs: class bytes, Mixin bytes, policy, mapping fingerprint, and InterMed platform component fingerprints. Any change to any input automatically invalidates that entry.

Setting to `false` causes every class to be re-resolved on every launch. Useful during development of Mixins or when debugging unexpected caching behaviour.

```properties
aot.cache.enabled=true
```

---

## 2. Mixin conflict resolution

### `mixin.conflict.policy`

| Type   | Default  | Values                          | Overridable via `-D` |
|--------|----------|---------------------------------|----------------------|
| string | `bridge` | `bridge` `merge` `overwrite` `fail` | yes              |

Determines the fallback strategy when two or more Mixin methods cannot be automatically merged:

- **`bridge`** — Generate a synthetic priority bridge method that calls all conflicting implementations in priority order (highest priority first). Game always starts; behaviour may differ from single-loader runs.
- **`merge`** — Attempt a linear superset merge (one method's bytecode subsumes the others). Falls back to `bridge` if the merge fails.
- **`overwrite`** — The highest-priority mod's implementation wins; all others are silently dropped.
- **`fail`** — Throw `MixinConflictException` and abort startup on any unresolvable conflict. Intended for CI/CD pipelines and integration testing.

```properties
mixin.conflict.policy=bridge
```

---

### `mixin.ast.reclaim.enabled`

| Type    | Default | Overridable via `-D` |
|---------|---------|----------------------|
| boolean | `true`  | yes                  |

When `true`, intermediate AST metadata generated during Mixin resolution (class trees, instruction lists, frame data) is reclaimed from the heap after transformation completes. Reduces peak heap usage by 50–200 MB for large modpacks.

Set to `false` only when using post-startup diagnostic tools that inspect the Mixin resolution graph.

```properties
mixin.ast.reclaim.enabled=true
```

---

## 3. Security and capabilities

### `security.strict.mode`

| Type    | Default | Overridable via `-D` |
|---------|---------|----------------------|
| boolean | `true`  | yes                  |

When `true`, any mod attempting an operation it has not been explicitly granted throws `CapabilityDeniedException`. If InterMed cannot attribute a sensitive operation to a known mod or trusted host/platform stack, the operation is denied as an unattributed caller. All decisions are written to `logs/intermed-security.log`.

When `false`, violations are logged but not thrown. Use only for initial porting of legacy mods that have not yet declared capability grants.

Compatibility harness sweeps may intentionally use this setting, but a permissive
compatibility run must not be interpreted as proof that a deployment is secure.

```properties
security.strict.mode=true
```

---

### `security.legacy.broad.permissions.enabled`

| Type    | Default | Overridable via `-D` |
|---------|---------|----------------------|
| boolean | `false` | yes                  |

When `true`, grants all capabilities to any mod that does not have an explicit `mod.grant.<modId>` entry. Effectively bypasses the capability model for legacy mods.

**Never enable on public/shared servers or security-sensitive alpha runs.** A mod with `PROCESS_SPAWN` or `NATIVE_LIBRARY` can escape the JVM sandbox.

This flag is acceptable only for isolated compatibility experiments or private trusted
setups. It is incompatible with secure-by-default claims.

```properties
security.legacy.broad.permissions.enabled=false
```

---

## 4. ClassLoader DAG

### `classloader.dynamic.weak.edges.enabled`

| Type    | Default | Overridable via `-D` |
|---------|---------|----------------------|
| boolean | `true`  | yes                  |

Controls runtime installation of weak ClassLoader edges. When a `ClassNotFoundException` occurs in a mod's ClassLoader and this is `true`, InterMed calls `LifecycleManager.tryInstallWeakEdge()` which probes peer ClassLoaders for the missing type. The edge is installed only if `WeakPeerPolicy` allows it (i.e. the type is in the target mod's exported API surface).

Set to `false` to disable all dynamic edge installation. ClassNotFoundException will then propagate normally.

```properties
classloader.dynamic.weak.edges.enabled=true
```

---

## 5. Dependency resolver

### `resolver.allow.fallback`

| Type    | Default | Overridable via `-D` |
|---------|---------|----------------------|
| boolean | `false` | yes                  |

When `true`, if the PubGrub resolver cannot find a required class in the expected ClassLoader layer, it falls through to the platform ClassLoader (the raw Forge or Fabric class space). This allows mods with undeclared cross-platform dependencies to load at the cost of weakening the isolation boundary.

Should remain `false` for correctness. Enable only for temporarily accommodating mods that have hard-coded cross-platform class references.

```properties
resolver.allow.fallback=false
```

---

## 6. Sandbox / polyglot runtime

### `sandbox.espresso.enabled`

| Type    | Default | Overridable via `-D` |
|---------|---------|----------------------|
| boolean | `true`  | yes                  |

Enables the GraalVM Espresso backend (JVM-in-JVM) for executing sandboxed JVM bytecode. Requires GraalVM JDK 21 or newer on the host. When `false`, JVM sandboxing falls back to isolation via ClassLoader boundaries only.

```properties
sandbox.espresso.enabled=true
```

---

### `sandbox.wasm.enabled`

| Type    | Default | Overridable via `-D` |
|---------|---------|----------------------|
| boolean | `true`  | yes                  |

Enables the Chicory WebAssembly backend for executing `.wasm` mod blobs. When `false`, Wasm modules are rejected at load time.

```properties
sandbox.wasm.enabled=true
```

---

### `sandbox.native.fallback.enabled`

| Type    | Default | Overridable via `-D` |
|---------|---------|----------------------|
| boolean | `false` | yes                  |

When `true`, allows sandboxed code that requires native libraries to load them directly into the host JVM process as a fallback. This is unsafe (native code is not sandboxed) and should only be used for mods that cannot be ported to Espresso or Wasm.

```properties
sandbox.native.fallback.enabled=false
```

---

### `sandbox.shared.region.bytes`

| Type | Default | Minimum | Overridable via `-D` |
|------|---------|---------|----------------------|
| int  | `4096`  | `1024`  | yes                  |

Size in bytes of each pre-allocated off-heap shared memory region used for IPC between the host JVM and sandbox instances. Each invocation borrows one region from the pool. Larger values allow more complex game-state graphs to be passed without reallocation; smaller values reduce memory footprint when sandboxed calls are infrequent.

The effective minimum is clamped to 1024 bytes at runtime regardless of the configured value.

```properties
sandbox.shared.region.bytes=4096
```

---

### `sandbox.shared.region.pool.max`

| Type | Default | Minimum | Overridable via `-D` |
|------|---------|---------|----------------------|
| int  | `32`    | `1`     | yes                  |

Maximum number of off-heap shared memory regions held in the pool. When all regions are in use, the acquiring thread blocks until one is returned. Increase for high-concurrency servers; decrease to reduce memory consumption.

Total off-heap memory reserved = `sandbox.shared.region.bytes × sandbox.shared.region.pool.max`.
With defaults: `4096 × 32 = 128 KB`.

```properties
sandbox.shared.region.pool.max=32
```

---

## 7. Virtual File System (VFS)

### `vfs.enabled`

| Type    | Default | Overridable via `-D` |
|---------|---------|----------------------|
| boolean | `true`  | yes                  |

When `true`, InterMed routes managed resource/data pack access through the VFS conflict resolution layer. Full ResourceManager/DataPack lifecycle proof is tracked as a later launch criterion. Disabling this reverts to standard per-loader resource loading (no cross-loader asset merging).

```properties
vfs.enabled=true
```

---

### `vfs.conflict.policy`

| Type   | Default              | Values                                                   | Overridable via `-D` |
|--------|----------------------|----------------------------------------------------------|----------------------|
| string | `merge_then_priority`| `merge_then_priority` `priority_override` `fail_on_conflict` | yes              |

Controls how the VFS resolves conflicts between resources from different mods:

- **`merge_then_priority`** — For JSON resources: attempt a deep JSON Patch merge of all conflicting versions. If the merge succeeds, the result is used. If it fails (e.g. incompatible value types at the same key), the highest-priority mod's version wins.
- **`priority_override`** — Always use the highest-priority mod's version. No merging attempted.
- **`fail_on_conflict`** — Throw on any unresolved resource conflict. Intended for CI.

Note: `lang` files always use deep key merge regardless of this setting. `tag` files always use array union.

```properties
vfs.conflict.policy=merge_then_priority
```

---

### `vfs.priority.mods`

| Type              | Default   | Overridable via `-D` |
|-------------------|-----------|----------------------|
| comma-separated string | `""` | yes (`vfs.priority.mods`) |

Explicit ordered list of mod IDs that should win resource conflicts, from highest to lowest priority. Mods not in this list fall back to their declared `intermedPriority` value.

```properties
vfs.priority.mods=my-texture-pack,dungeon-overhaul,base-mod
```

---

### `vfs.cache.dir`

| Type   | Default                             | Overridable via `-D` |
|--------|-------------------------------------|----------------------|
| path   | `<gameDir>/.intermed/vfs`           | yes                  |

Directory where the VFS writes its materialised overlay pack and diagnostics report. If relative, resolved against `runtime.game.dir`.

```properties
vfs.cache.dir=.intermed/vfs
```

---

## 8. Observability

### `observability.ewma.threshold.ms`

| Type   | Default  | Overridable via `-D` |
|--------|----------|----------------------|
| double | `55.0`   | yes                  |

Bridge calls whose EWMA latency exceeds this threshold (in milliseconds) are logged as performance warnings. Set to `0.0` to log every bridged call (very verbose). Set to a large value (e.g. `10000.0`) to suppress all PERF warnings.

```properties
observability.ewma.threshold.ms=55.0
```

---

### `metrics.prometheus.enabled`

| Type    | Default | Overridable via `-D` |
|---------|---------|----------------------|
| boolean | `false` | yes                  |

When `true`, starts a Prometheus HTTP endpoint at `http://0.0.0.0:<metrics.prometheus.port>/metrics`.

```properties
metrics.prometheus.enabled=false
```

---

### `metrics.prometheus.port`

| Type | Default | Overridable via `-D` |
|------|---------|----------------------|
| int  | `9090`  | yes                  |

Port for the Prometheus metrics endpoint. Only relevant when `metrics.prometheus.enabled = true`.

```properties
metrics.prometheus.port=9090
```

---

### `metrics.otel.enabled`

| Type    | Default | Overridable via `-D` |
|---------|---------|----------------------|
| boolean | `false` | yes                  |

When `true`, writes OpenTelemetry trace spans as JSON to `metrics.otel.path`. Each span covers one bridge call or Mixin resolution cycle.

```properties
metrics.otel.enabled=false
```

---

### `metrics.otel.path`

| Type | Default                                       | Overridable via `-D` |
|------|-----------------------------------------------|----------------------|
| path | `<gameDir>/logs/intermed-metrics.json`        | yes                  |

Output file for OTel JSON traces. If relative, resolved against `runtime.game.dir`. The file is appended to and rotated when it exceeds 50 MB.

```properties
metrics.otel.path=logs/intermed-metrics.json
```

---

## 9. Runtime paths and environment

### `runtime.game.dir`

| Type | Default           | Overridable via `-D` |
|------|-------------------|----------------------|
| path | `user.dir` (CWD)  | yes                  |

Root of the Minecraft game directory. All relative paths in the config are resolved against this value.

```properties
runtime.game.dir=.
```

---

### `runtime.config.dir`

| Type | Default                        | Overridable via `-D` |
|------|--------------------------------|----------------------|
| path | `<runtime.game.dir>/config`    | yes                  |

Directory where InterMed reads and writes its configuration files, including `intermed-runtime.properties` and `intermed-vfs-overrides.json`.

```properties
runtime.config.dir=config
```

---

### `runtime.mods.dir`

| Type | Default                           | Overridable via `-D`                                         |
|------|-----------------------------------|--------------------------------------------------------------|
| path | `<runtime.game.dir>/intermed_mods`| yes (also `-Dintermed.modsDir`)                              |

Directory scanned for mod JARs on startup. All JARs in this directory (non-recursive) are processed regardless of loader type.

```properties
runtime.mods.dir=intermed_mods
```

---

### `runtime.env`

| Type   | Default  | Values            | Overridable via `-D`                                      |
|--------|----------|-------------------|-----------------------------------------------------------|
| string | `client` | `client` `server` | yes (also `-Dintermed.env`, `-Dfabric.env`)               |

Declares whether the runtime is running in client or server mode. Affects ClassLoader visibility rules (client-side classes are hidden on the server), Mixin applicability, and lifecycle event routing.

```properties
runtime.env=client
```

---

## 10. Event bus (JVM system properties)

These settings are JVM system properties only (no `.properties` file equivalent). Set them in your launch script via `-D`.

### `intermed.events.ring-buffer.capacity`

| Type | Default | Constraints     |
|------|---------|-----------------|
| int  | `1024`  | Must be power of two |

Capacity of the lock-free ring buffer used by `LockFreeEvent`. When the buffer is full, publishing threads spin-wait. Increase for high-throughput event scenarios (e.g. particle-heavy mods, chunk loading events).

```
-Dintermed.events.ring-buffer.capacity=4096
```

---

### `intermed.events.dispatcher-cache.max-size`

| Type | Default |
|------|---------|
| int  | `256`   |

Maximum number of compiled dispatcher classes held in the `DispatchSignature` cache. Each entry is a ByteBuddy-generated class keyed by `(listenerType, methodName, descriptor, listenerCount)`. Entries are evicted LRU-style when the limit is reached. Increase if you have many distinct event types with frequently changing listener counts.

```
-Dintermed.events.dispatcher-cache.max-size=512
```

---

## 11. Mod-level overrides

These keys follow a `<prefix>.<modId>=<value>` naming convention and apply to individual mods.

### `mod.priority.<modId>`

| Type | Default |
|------|---------|
| int  | `1000`  |

Sets the resolution priority for a specific mod. Higher values win conflicts. Used for both Mixin conflict resolution and VFS resource priority.

```properties
mod.priority.my-overhaul-mod=2000
mod.priority.base-compatibility-mod=500
```

---

### `mod.grant.<modId>`

| Type                    | Default |
|-------------------------|---------|
| comma-separated strings | `""`    |

Grants the specified capabilities to a mod. Values are capability names from the `Capability` enum.

```properties
mod.grant.network-mod=NETWORK_CONNECT
mod.grant.file-sync-mod=FILE_READ,FILE_WRITE
mod.grant.debug-tool=REFLECTION_ACCESS,UNSAFE_ACCESS
```

---

## 12. Sample configuration files

### Minimal server configuration

```properties
# intermed-runtime.properties — minimal dedicated server setup
runtime.env=server
mixin.conflict.policy=bridge
security.strict.mode=true
aot.cache.enabled=true
vfs.enabled=true
vfs.conflict.policy=merge_then_priority
sandbox.espresso.enabled=false
sandbox.wasm.enabled=false
metrics.prometheus.enabled=true
metrics.prometheus.port=9090
observability.ewma.threshold.ms=100.0
```

### Development / CI configuration

```properties
# intermed-runtime.properties — CI pipeline
aot.cache.enabled=false
mixin.conflict.policy=fail
mixin.ast.reclaim.enabled=false
security.strict.mode=true
vfs.conflict.policy=fail_on_conflict
sandbox.espresso.enabled=true
sandbox.wasm.enabled=true
metrics.otel.enabled=true
metrics.otel.path=build/reports/intermed-metrics.json
observability.ewma.threshold.ms=20.0
```

### Large modpack client configuration

```properties
# intermed-runtime.properties — 100+ mods client
runtime.env=client
aot.cache.enabled=true
mixin.conflict.policy=bridge
mixin.ast.reclaim.enabled=true
security.strict.mode=true
security.legacy.broad.permissions.enabled=false
classloader.dynamic.weak.edges.enabled=true
vfs.enabled=true
vfs.conflict.policy=merge_then_priority
vfs.priority.mods=core-library,compat-layer
sandbox.espresso.enabled=false
sandbox.wasm.enabled=true
sandbox.shared.region.bytes=8192
sandbox.shared.region.pool.max=16
observability.ewma.threshold.ms=55.0
metrics.prometheus.enabled=false

# Per-mod grants
mod.grant.map-viewer-mod=FILE_READ,NETWORK_CONNECT
mod.grant.crash-reporter=FILE_WRITE,NETWORK_CONNECT
```

### VFS overrides file (`config/intermed-vfs-overrides.json`)

```json
{
  "rules": [
    {
      "comment": "Let the texture pack always win stone variants",
      "pattern": "assets/minecraft/textures/block/stone*",
      "policy": "priority_override",
      "winnerModId": "my-texture-pack"
    },
    {
      "comment": "Merge all block tags additively",
      "pattern": "data/minecraft/tags/blocks/**",
      "policy": "merge",
      "winnerModId": null
    },
    {
      "comment": "Dungeon mod owns all loot tables under its namespace",
      "pattern": "data/dungeon-mod/loot_tables/**",
      "policy": "priority_override",
      "winnerModId": "dungeon-mod"
    },
    {
      "comment": "Strict check on recipes to surface conflicts in CI",
      "pattern": "data/**/recipes/**",
      "policy": "fail_on_conflict",
      "priorityMods": ["recipe-tweaks", "dungeon-mod"]
    }
  ]
}
```

Glob patterns: `**` matches any number of path segments; `*` matches within a single segment. Rules are evaluated top-to-bottom; first match wins.
