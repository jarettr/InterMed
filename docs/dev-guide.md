# InterMed v8.0 Alpha Snapshot — Developer Guide

This guide covers the current `v8.0-alpha-snapshot` runtime surface for mod authors experimenting with InterMed: how the ClassLoader DAG works, how to write Mixins that are easier to diagnose in multi-loader conflict resolution, how to work with the remapping and reflection layers, and how to use the polyglot sandbox API. It documents the alpha surface, not a full Forge/Fabric/NeoForge parity guarantee.

---

## Table of Contents

1. [Architecture overview](#1-architecture-overview)
2. [ClassLoader DAG and mod isolation](#2-classloader-dag-and-mod-isolation)
3. [Writing conflict-safe Mixins](#3-writing-conflict-safe-mixins)
4. [Dynamic remapping and reflection](#4-dynamic-remapping-and-reflection)
5. [Sandbox API](#5-sandbox-api)
6. [Capability / security model](#6-capability--security-model)
7. [VFS and resource conflict resolution](#7-vfs-and-resource-conflict-resolution)
8. [Event bus API](#8-event-bus-api)
9. [Registry API](#9-registry-api)
10. [Observability hooks](#10-observability-hooks)
11. [Test automation and launch strategy](#11-test-automation-and-launch-strategy)

---

## 1. Architecture overview

InterMed sits between the JVM and Minecraft's class space. At startup it:

1. Discovers every mod JAR in `intermed_mods/` (or the configured `runtime.mods.dir`).
2. Resolves the dependency graph via PubGrub SAT, clustering libraries into shared ClassLoaders using Welsh–Powell graph colouring.
3. Assigns every mod its own `LazyInterMedClassLoader` node in a DAG (parent / peer / weak-peer links).
4. Transforms every class on first load: remapping names, applying Mixin patches via `MixinASTAnalyzer`, running `ReflectionTransformer`, and routing resources through `VirtualFileSystemRouter`.
5. Publishes all events through a lock-free LMAX-style ring buffer with ByteBuddy-compiled per-listener dispatch.

The intended path is transparent to a mod that has no Mixin conflicts with other mods. During alpha hardening, the sections below also explain how to diagnose cases where compatibility is incomplete.

---

## 2. ClassLoader DAG and mod isolation

### 2.1 Node types

| Link type   | Visibility direction                                 | Typical use                                     |
|-------------|------------------------------------------------------|-------------------------------------------------|
| `parent`    | Child sees parent; parent does not see child         | Standard Java delegation; Minecraft core        |
| `peer`      | Bidirectional (both see each other)                  | Tightly coupled mod pairs (declared in manifest)|
| `weak-peer` | Constrained bidirectional; API interfaces only       | Optional integration (runtime feature detection)|

Weak-peer edges are installed lazily when a `ClassNotFoundException` occurs and `classloader.dynamic.weak.edges.enabled = true` (the default). They are gated by `WeakPeerPolicy` which permits only types whose simple name is listed in the target mod's `intermed.api.exports` manifest attribute.

### 2.2 Declaring exports

Add the following to your mod JAR's `META-INF/MANIFEST.MF`:

```
InterMed-API-Exports: com.example.mymod.api.MyModApi,com.example.mymod.api.MyEvent
```

Or use the `intermed.json` descriptor (preferred):

```json
{
  "modId": "my-mod",
  "version": "1.2.3",
  "apiExports": [
    "com.example.mymod.api.MyModApi",
    "com.example.mymod.api.MyEvent"
  ],
  "peerDependencies": ["other-mod"],
  "weakPeerDependencies": ["optional-compat-mod"]
}
```

### 2.3 Peer vs. weak-peer

Use `peerDependencies` when your mod **requires** the other to be present. Use `weakPeerDependencies` when you want to call into another mod only if it happens to be loaded. The runtime will install a weak edge but restrict it to exported API types, preventing classloader leaks.

### 2.4 Diagnosing ClassLoader issues

Enable verbose logging with the JVM flag `-Dintermed.classloader.verbose=true`. Each load attempt prints the full delegation chain. Look for:

- `[CL-WEAK] installed edge …` — a dynamic weak edge was created.
- `[CL-PEER] resolved … via peer …` — peer delegation succeeded.
- `[CL-FAIL] no loader …` — class not found anywhere; check exports.

---

## 3. Writing conflict-safe Mixins

InterMed uses a custom Mixin fork backed by `ResolutionEngine` which resolves conflicts between Mixins from different mods automatically. Understanding the resolution order helps you write Mixins that behave predictably.

### 3.1 Resolution strategy order

When two or more Mixins target the same method the engine tries strategies in order:

| Priority | Strategy           | When applied                                                              |
|----------|--------------------|---------------------------------------------------------------------------|
| 1        | Additive merge     | `@Inject` at different injection points; all injectors run               |
| 2        | Duplicate elision  | Byte-for-byte identical injectors; only one copy runs                     |
| 3        | Direct replace     | One `@Overwrite` and all others are `@Shadow` / read-only                |
| 4        | Linear superset    | One method is a bytecode superset of all others (subsumes them safely)   |
| 5        | Semantic contract  | Render pipeline or world-state mutation zone detected; strict ordering    |
| 6        | Priority bridge    | Unresolvable conflict; a synthetic bridge calls mods in priority order    |

### 3.2 Conflict policy

The system-wide policy is set via `mixin.conflict.policy` in `intermed-runtime.properties`:

| Value       | Behaviour                                                                  |
|-------------|----------------------------------------------------------------------------|
| `bridge`    | **(default)** Generate a priority bridge for unresolvable conflicts        |
| `merge`     | Attempt linear superset merge; fall back to bridge on failure              |
| `overwrite` | Highest-priority mod wins; others are silently dropped                     |
| `fail`      | Throw `MixinConflictException` on any unresolvable conflict (CI/testing)   |

Per-method overrides are not yet supported. Use `fail` in integration tests to surface conflicts early.

### 3.3 Annotation quick reference

All standard Mixin annotations are supported. InterMed-specific behaviour:

**`@Inject`** — Multiple injectors at the same `at` point are merged additively. Order among mods is determined by `priority` (higher wins earlier) then `modId` lexicographic tiebreak.

**`@Overwrite`** — Only one `@Overwrite` per method is allowed. If two mods both declare `@Overwrite` for the same method, the engine falls back to strategy 6 (bridge) where the higher-priority mod's overwrite is called first and the lower-priority one is dropped, unless policy is `fail`.

**`@Redirect`** — Multiple redirects at the same call site use the chain pattern: each redirect wraps the previous result. Order matches injection priority.

**`@ModifyArg` / `@ModifyArgs`** — Chained in priority order.

**`@WrapOperation` / `@WrapMethod`** — Fully supported; treated as priority-ordered wrappers.

### 3.4 Semantic zones

Methods identified as being in the **render pipeline** (`RENDER_PIPELINE`) or **world state mutation** (`WORLD_STATE`) semantic zones are subject to strict ordering regardless of the annotation type. The engine detects these zones from bytecode signals:

- Owner class path contains `/client/renderer/`, `/blaze3d/`, `/server/level/`, etc.
- Method descriptor contains types like `PoseStack`, `ServerLevel`, `BlockPos`.
- Bytecode writes fields, uses local variables across control flow, or calls render/world API methods.

If your Mixin targets a semantic zone method and you need predictable ordering, set an explicit `priority` value. The default priority is 1000; lower numbers run first.

### 3.5 Detecting and fixing bridge artifacts

When a priority bridge is generated, InterMed logs:

```
[AST] Generated priority bridge for com/example/MyClass#myMethod(...)V  mods=[mod-a, mod-b]
```

You can inspect the full conflict report at startup by setting `-Dintermed.mixin.conflict.report=true`. The report includes the full conflict reason, the strategy that was selected, and which bytecode signals triggered the decision.

---

## 4. Dynamic remapping and reflection

InterMed translates class and member names between Fabric (Intermediary) and Forge (SRG / MojMap) namespaces transparently. Most code needs no changes. The following cases require attention.

### 4.1 String-based class loading

**Do not use raw string literals for class names if they might be platform-specific:**

```java
// BAD — will fail on one platform
Class<?> clazz = Class.forName("net.minecraft.world.level.Level");

// GOOD — let the remapper handle it
Class<?> clazz = Class.forName("net.minecraft.world.level.Level"); // OK if you're sure
// or use the InterMed remapping API:
String canonical = InterMedRemapper.translateRuntimeString("net.minecraft.world.level.Level");
Class<?> clazz = Class.forName(canonical);
```

`ReflectionTransformer` automatically instruments calls to `Class.forName`, `ClassLoader.loadClass`, and `MethodHandles.Lookup.findClass` by injecting `InterMedRemapper.translateRuntimeString()` around them. This means most reflective code is handled without changes.

### 4.2 String concatenation with class names

`ReflectionTransformer` performs full dataflow analysis on `StringBuilder` chains and `invokedynamic` string concatenation (`StringConcatFactory`). If it detects a suspicious class/member name being assembled dynamically, it wraps the final string. However, it cannot track names built from user input or config files at runtime — in that case call `InterMedRemapper.translateRuntimeString()` explicitly before passing the string to reflection APIs.

### 4.3 Reflection on fields and methods

The transformer covers:

- `Class.getField` / `getDeclaredField`
- `Class.getMethod` / `getDeclaredMethod`
- `MethodHandles.Lookup.findVirtual` / `findStatic` / `findSpecial` / `findGetter` / `findSetter`

If you build a method descriptor string dynamically (e.g. from a config file), wrap the component names manually:

```java
String remappedOwner = InterMedRemapper.translateRuntimeString(ownerClass);
String remappedName  = InterMedRemapper.translateRuntimeString(methodName);
Method m = Class.forName(remappedOwner).getDeclaredMethod(remappedName, paramTypes);
```

### 4.4 Mixin `@Shadow` and `@Accessor`

Shadow names are remapped automatically during Mixin transformation. You only need to use the correct name for the **target namespace** (usually Intermediary on Fabric, SRG on Forge). The `@Shadow` annotation's `remap` attribute is respected; setting `remap = false` skips the transformer.

---

## 5. Sandbox API

InterMed provides polyglot sandboxes for executing untrusted or polyglot mod code. Two backends are available:

| Backend    | Runtime           | JVM system property              |
|------------|-------------------|----------------------------------|
| GraalVM    | Espresso (JVM-in-JVM)| `sandbox.espresso.enabled=true` |
| WebAssembly| Chicory Wasm      | `sandbox.wasm.enabled=true`      |

### 5.1 Obtaining a sandbox

```java
import org.intermed.core.sandbox.PolyglotSandboxManager;
import org.intermed.core.sandbox.SandboxHandle;

SandboxHandle sandbox = PolyglotSandboxManager.acquire("my-mod");
```

`acquire()` selects the appropriate backend (GraalVM if `.class` / `.jar`, Wasm if `.wasm`) based on the mod's declared `sandboxMode` in `intermed.json`.

### 5.2 Passing game state into the sandbox

Use `SandboxGameStateSerializer` to build a `SharedStateGraph` from live Minecraft objects and pass it into the sandbox without deep serialization:

```java
import org.intermed.core.sandbox.SandboxGameStateSerializer;
import org.intermed.core.sandbox.SandboxSharedExecutionContext.SharedStateGraph;

// Full game context
SharedStateGraph state = SandboxGameStateSerializer.buildFor(
    "my-mod",         // modId
    "onPlayerTick",   // target method label
    playerObject,     // net.minecraft.world.entity.player.Player (or compatible)
    levelObject,      // net.minecraft.world.level.Level
    blockPosObject    // net.minecraft.core.BlockPos
);

sandbox.execute(state);
```

For invocations that do not need player/world context:

```java
SharedStateGraph state = SandboxGameStateSerializer.invocationOnly("my-mod", "onServerTick");
sandbox.execute(state);
```

### 5.3 Builder API

The builder gives full control over the graph structure:

```java
SharedStateGraph state = SandboxGameStateSerializer.builder()
    .modId("my-mod")
    .target("customEvent")
    .player(playerObject)
    .world(levelObject)
    .node(SandboxGameStateSerializer.NODE_ITEM_STACK, "heldItem", heldItemObject)
    .build();
```

Custom node types can be registered by using string constants directly; the property map is open-ended.

### 5.4 Off-heap transport

`SandboxSharedExecutionContext` uses the JDK 22+ Foreign Function & Memory (FFM) API (`java.lang.foreign.Arena`) when available, falling back to `ByteBuffer.allocateDirect()` on older JDKs. Pooled regions are pre-allocated at startup (size: `sandbox.shared.region.bytes`, pool depth: `sandbox.shared.region.pool.max`).

The binary layout is fixed: header (64 bytes), node records (56 bytes each), property records (20 bytes each). Do **not** write to the underlying `MemorySegment` directly; always go through `SharedStateGraph` / `SharedStateNode` / `SandboxSharedExecutionContext.bind()`.

### 5.5 Capability grant for sandboxed code

Sandboxed code runs with **no capabilities by default**. Grant capabilities in your mod's `intermed.json`:

```json
{
  "modId": "my-mod",
  "sandboxGrants": {
    "FILE_READ": ["./config/my-mod/**"],
    "NETWORK_CONNECT": ["api.example.com:443"]
  }
}
```

Attempting a capability without a grant causes `CapabilityDeniedException`. Enable verbose denial logging with `-Dintermed.security.verbose=true`.

---

## 6. Capability / security model

InterMed's capability layer is implemented as a ByteBuddy Java agent that intercepts sensitive JDK operations. Attribution is performed via `StackWalker` — the first frame belonging to a known mod ID is used.

### 6.1 Capability enum

| Capability         | Guarded operations                                          |
|--------------------|-------------------------------------------------------------|
| `FILE_READ`        | `FileInputStream`, `Files.newInputStream`, `Path.toFile`    |
| `FILE_WRITE`       | `FileOutputStream`, `Files.write`, `Files.newOutputStream`  |
| `NETWORK_CONNECT`  | `Socket.connect`, `URL.openConnection`, `HttpClient.send`   |
| `MEMORY_ACCESS`    | `sun.misc.Unsafe`, `MemorySegment.ofAddress`                |
| `UNSAFE_ACCESS`    | `Unsafe.allocateMemory`, `Unsafe.putAddress`                |
| `REFLECTION_ACCESS`| `Field.setAccessible`, `Module.addOpens` (dynamic)          |
| `PROCESS_SPAWN`    | `Runtime.exec`, `ProcessBuilder.start`                      |
| `NATIVE_LIBRARY`   | `System.loadLibrary`, `Runtime.load`                        |

### 6.2 Strict vs. permissive mode

`security.strict.mode = true` (default): all capability checks are enforced. Any violation throws `CapabilityDeniedException` and is logged to the security audit trail. If caller attribution fails and the stack is not a trusted host/platform stack, the operation is denied as an unattributed caller.

`security.strict.mode = false`: violations are logged but not thrown. Use only during initial development/porting of legacy mods.

`security.legacy.broad.permissions.enabled = true`: grants all capabilities to any mod that does not declare an explicit grant list. **Never use on shared/public servers or when validating the strict security posture.**

The automation harness may still use permissive settings for compatibility sweeps,
but those runs must be treated as a different verification lane from strict-security
regression. A green compatibility sweep is not a substitute for fail-closed checks.

### 6.3 Async attribution

InterMed propagates both Thread Context ClassLoader and capability attribution through
its `TcclInterceptor` wrappers. The runtime bytecode transformer also wraps common
mod-side async calls such as `Executor.execute`, simple `submit(...)`,
`CompletableFuture.runAsync`, `CompletableFuture.supplyAsync`, `ForkJoinPool`
single-argument submissions, and `new Thread(Runnable)`.

This is the supported alpha path for ordinary async work. More complex overloads
where the task is not the top stack argument, custom native schedulers, and hostile
manual context clearing still need dedicated field-style validation before they can
be considered field-tested.

### 6.4 Native library routing

Transformed direct calls to `System.load`, `System.loadLibrary`, `Runtime.load`,
and `Runtime.loadLibrary` are checked for `NATIVE_LIBRARY` and then routed through
the singleton `NativeLinkerNode`. The node canonicalizes paths, deduplicates repeat
loads, and fails early when two mods try to claim the same logical native library
from different physical paths.

`LazyInterMedClassLoader.findLibrary()` is deliberately diagnostic-only: it records
the resolved canonical path for ownership/conflict reporting, but it does not call
`System.load(...)` itself. This avoids a double-load when the JVM is already inside
its native library resolution path. Real JNA/JNI-heavy public mods still require
field-style validation before this can be treated as field-tested.

---

## 7. VFS and resource conflict resolution

InterMed's alpha VFS indexes managed `assets/` and `data/` entries from loaded mod JARs and prepares conflict-resolved overlays before the game sees those resources. Full ResourceManager/DataPack lifecycle proof is tracked separately in launch criteria.

### 7.1 Resource kinds and merge strategy

| Kind          | Default strategy        |
|---------------|-------------------------|
| `lang`        | Deep key merge          |
| `tag`         | Array union (additive)  |
| `recipe`      | Priority override        |
| `loot_table`  | Priority override        |
| `advancement` | Priority override        |
| All others    | Per `vfs.conflict.policy`|

`vfs.conflict.policy` values:

| Value                  | Behaviour                                                     |
|------------------------|---------------------------------------------------------------|
| `merge_then_priority`  | **(default)** JSON Patch merge; fall back to priority winner  |
| `priority_override`    | Highest-priority mod always wins                              |
| `fail_on_conflict`     | Throw on any resource conflict (useful for CI)                |

### 7.2 Override rules file

Place `intermed-vfs-overrides.json` in your game config directory to manually resolve specific conflicts:

```json
{
  "rules": [
    {
      "pattern": "data/minecraft/tags/blocks/mineable/pickaxe.json",
      "policy": "merge",
      "winnerModId": null
    },
    {
      "pattern": "assets/minecraft/textures/block/stone.*",
      "policy": "priority_override",
      "winnerModId": "texture-pack-mod"
    },
    {
      "pattern": "data/**/loot_tables/**",
      "policy": "fail_on_conflict",
      "priorityMods": ["dungeon-mod", "treasure-mod"]
    }
  ]
}
```

Glob patterns support `**` (any path depth) and `*` (single segment). Rules are evaluated in order; the first match wins.

### 7.3 Diagnostics report

InterMed writes a diagnostics report to `<gameDir>/.intermed/vfs/diagnostics.json` on each startup listing every resolved conflict, the strategy used, and suggested `intermed-vfs-overrides.json` entries. Review this file after adding new mods.

---

## 8. Event bus API

InterMed provides a unified event bus that replaces both Fabric's EventFactory and Forge's EventBus. Events are dispatched through a lock-free ring buffer with ByteBuddy-compiled listener dispatch.

### 8.1 Declaring an event

```java
public record MyTickEvent(Level level, long tick) {}
```

Plain POJOs and records are both fine. No base class or annotation required.

### 8.2 Registering a listener

```java
import org.intermed.core.monitor.LockFreeEvent;

LockFreeEvent<MyTickEvent> tickBus = LockFreeEvent.create(MyTickEvent.class);

tickBus.register(event -> {
    System.out.println("Tick: " + event.tick());
});
```

The dispatcher is compiled once per unique listener count (keyed by `(listenerType, methodName, descriptor, count)`). Adding or removing a listener triggers a one-time recompile of the dispatch class — subsequent dispatches are direct `INVOKEINTERFACE` sequences with no loop overhead.

### 8.3 Publishing an event

```java
tickBus.publish(new MyTickEvent(level, tick));
```

Publication is non-blocking. If the ring buffer is full (capacity: `intermed.events.ring-buffer.capacity`, default 1024), the publishing thread spins until a slot is available.

### 8.4 Cross-platform event bridges

For Fabric lifecycle events use `FabricLifecycleBridge`; for Forge events use `ForgeEventBridge`. Both translate platform-specific event types into InterMed events transparently — you do not need platform-specific code in your mod.

---

## 9. Registry API

InterMed's registry uses a CHD Minimal Perfect Hash Function (MPHF) backed by a ByteBuddy-compiled lookup class for O(1) collision-free registry resolution.

### 9.1 Looking up a registry entry

```java
import org.intermed.core.bridge.InterMedRegistry;

int id = InterMedRegistry.lookupId("minecraft:stone");
Object entry = InterMedRegistry.lookupEntry("my-mod:my_block");
```

The frozen lookup class is compiled at registry freeze time. All lookups after freeze are direct array reads with no hash collision overhead.

### 9.2 Registering entries

Registration must happen during the `REGISTER` lifecycle phase. Entries registered after freeze cause `IllegalStateException`.

```java
InterMedRegistry.register("my-mod:my_block", myBlockInstance);
```

### 9.3 Cross-platform registry bridging

`RegistryCache` caches cross-platform lookups. On first miss it probes both the Fabric and Forge registry APIs, translates names via `InterMedRemapper`, and caches the result. Subsequent calls are O(1).

---

## 10. Observability hooks

### 10.1 EWMA slow-call detection

Every platform-bridged call is timed with an exponentially weighted moving average (EWMA). Calls whose EWMA exceeds `observability.ewma.threshold.ms` (default 55 ms) are logged as warnings:

```
[PERF] Slow bridge call: FabricLifecycleBridge#onWorldLoad  ewma=67.3ms  threshold=55.0ms
```

Reduce the threshold to surface subtler regressions; set to `0.0` to log all bridged calls.

### 10.2 Prometheus metrics

Enable with `metrics.prometheus.enabled = true`. Metrics are exposed on `http://localhost:<metrics.prometheus.port>/metrics` (default port 9090). Available metrics:

- `intermed_bridge_call_duration_ms` — histogram of bridge call durations
- `intermed_mixin_resolve_duration_ms` — histogram of Mixin resolution times
- `intermed_aot_cache_hits_total` / `intermed_aot_cache_misses_total`
- `intermed_event_ring_buffer_used` — gauge of ring buffer utilisation
- `intermed_classloader_load_count_total` — counter by mod ID

### 10.3 OpenTelemetry traces

Enable with `metrics.otel.enabled = true`. Traces are written as JSON to `metrics.otel.path` (default `<gameDir>/logs/intermed-metrics.json`). Each trace span covers one bridge call or Mixin resolution cycle, tagged with `mod.id` and `platform`.

---

## 11. Test automation and launch strategy

InterMed should expose two different launch surfaces:

1. `InterMedLauncher` for one real game or dedicated-server instance.
2. `test-harness` for mass automation, resumable compatibility sweeps, and soak runs.

Do not try to solve both jobs with one oversized launcher.

### 11.1 Normal runtime launch

`InterMedLauncher` should stay thin. Its job is:

- attach `-javaagent`
- set InterMed runtime properties
- validate the environment with `doctor`
- run one concrete server or client process

For real user launch, prefer existing launcher profiles plus JVM arguments:

- client launchers: set `-javaagent:/path/to/InterMedCore-8.0-SNAPSHOT.jar`
- dedicated servers: use `java -javaagent:... -jar ... nogui`

That is enough for the runtime product. A custom GUI launcher can come later if there is a strong product reason, but it should not block compatibility work.

### 11.2 Automated compatibility testing

The automated system should remain a separate headless orchestrator. That is the role of `test-harness`.

Its responsibilities are:

- bootstrap Forge/Fabric base environments
- discover and cache mod corpora
- create isolated per-run directories
- spawn real JVMs with InterMed attached
- classify logs and collect structured results
- regenerate reports from persisted `results.json`

By design this harness is optimized for broad compatibility and reproducibility, not
for enforcing the strict runtime posture used by the dedicated `:app:strictSecurity`
lane. Keep those responsibilities separate.

This is a different lifecycle from a user launcher:

- automation needs concurrency, retries, cache reuse, and resume support
- user launch needs predictable startup and simple diagnostics

### 11.3 Recommended architecture

Keep the boundaries like this:

- `InterMedLauncher`: single-instance launch adapter
- `ServerProcessRunner`: test-process orchestration
- `ModRegistry` and bootstrap installers: corpus and environment preparation
- report readers/writers: resumability and CI artifact generation
- `diagnostics-bundle`: single zip artifact for external alpha triage, combining
  manifest, compatibility report, SBOM, dependency plan, security/config snapshots,
  selected logs, and existing runtime evidence reports
- `InterMedLauncher launch`: automatically emits that bundle on non-zero process
  exit unless `--no-diagnostics-bundle` is set; CI can pin the path with
  `--diagnostics-output`

If later you want stricter parity between automation and real launch, reuse the same launch-command semantics. But keep the harness as a separate tool instead of merging it into a user launcher.

### 11.4 Practical answer to "flag or own launcher?"

The practical answer is:

- for users: launcher flag / JVM args are enough
- for automated testing: a separate harness is the correct shape

So the right architecture is not "either a flag or a launcher". It is "flag for runtime integration, harness for automation".

### 11.5 Rollout order

Start with server-side automation first:

- easier to run headlessly
- fits CI much better
- validates most startup, dependency, remap, registry, and Mixin behavior

Add a dedicated client runner only when you specifically need render, GUI, or client-only mod coverage.
