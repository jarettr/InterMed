# InterMed v8.0.0-alpha.2 Release Notes

InterMed `v8.0.0-alpha.2` is a field-hardening alpha for the frozen Minecraft
`1.20.1` runtime path. This drop focuses on the first real launcher/client
executions, deeper Forge/Fabric bridge behavior, and stronger runtime
diagnostics around the places where public modpacks still break.

It is still not a production release. Treat it as a tester and developer build.

## What Improved

- Forge and NeoForge bridge listeners can now attach against live runtime event
  buses, which makes startup less dependent on static class identity.
- Forge client UX is better: InterMed-discovered Fabric mods can be mirrored
  into the Forge Mod List UI and appended to crash reports with InterMed marks.
- Fabric resource/data packs discovered by InterMed can now be mounted through
  Forge pack repository hooks instead of relying on a narrower asset path.
- Nested jar-in-jar discovery is more realistic for public modpacks: embedded
  modules are deduplicated, linked back to their owners, and wired into safer
  DAG parent/weak-peer relationships.
- Runtime remapping is broader: the dictionary parser now uses TSRG member
  bridges when available, symbolic reflection can resolve classes/methods/fields
  lazily at runtime, and reflection-heavy code paths have a dedicated fallback.
- Registry compatibility widened for mapped aliases and transformed call sites,
  with clearer freeze diagnostics when dynamic lookup rewriting did or did not
  activate before the registry graph froze.
- The Fabric Loader compatibility layer now exposes richer metadata such as
  versions, dependency declarations, contact info, and custom values used by
  real mods at runtime.
- Security bootstrap behavior is safer in early-start paths by using a
  bootstrap-safe runtime config view and granting manifest-only mods default
  config-directory read/write access instead of forcing immediate hard failure.
- Mixin hardening now includes accessor fallback rewriting for late/unprocessed
  accessor interfaces plus more resilient resource lookup in the custom mixin
  service.

## First Real-Run Observations

- Recent real launcher traces now reach the Forge client title-screen path with
  InterMed bridge activation, nested Fabric module discovery, pack mounting, and
  Forge Mod List mirroring visible in the logs.
- The latest attached field run also surfaced a remaining gap: some Fabric mods
  still initialize before InterMed has the registry state they expect. The
  current concrete example is `modonomicon`, which fails while resolving
  `minecraft:item` during background entrypoint initialization.
- External account/authentication failures from launcher or Mojang services are
  not considered resolved by this alpha and should be triaged separately from
  InterMed runtime compatibility.

## How To Install

1. Use Java 21.
2. Generate a launch kit or point your launcher at the release jar:

   ```bash
   java -jar InterMedCore-8.0.0-alpha.2.jar launch-kit --game-dir /path/to/.minecraft
   ```

3. Put test mods in `intermed_mods/`.
4. Keep diagnostics enabled during alpha runs.

See [docs/user-guide.md](user-guide.md) for the full walkthrough.

## How To Report Issues

- Use the closest GitHub issue template.
- Attach a diagnostics bundle whenever possible.
- Manual diagnostics bundle command:

  ```bash
  java -cp InterMedCore-8.0.0-alpha.2.jar org.intermed.launcher.InterMedLauncher diagnostics-bundle \
    --game-dir /path/to/game \
    --mods-dir /path/to/game/intermed_mods
  ```

Read [docs/alpha-triage.md](alpha-triage.md) and
[docs/known-limitations.md](known-limitations.md) before filing broad
compatibility or security claims.
