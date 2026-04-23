# Changelog

## v8.0.0-alpha.2

Alpha.2 is the first field-hardening drop after real client and launcher runs on the frozen Minecraft `1.20.1` line.

### Highlights

- Forge and NeoForge runtime probes now register bridge listeners against live mod buses instead of relying on static compile-time bus wiring.
- Forge client integration is broader: InterMed-discovered Fabric mods can be mirrored into the Forge Mod List UI and crash reports, and Fabric resource/data jars can be mounted through Forge pack repository hooks.
- Nested JAR handling is more realistic for public modpacks: jar-in-jar modules are deduplicated, wired back to their owners, and granted safer DAG parent/weak-peer relationships, including Fabric API peer linking.
- Remapping and reflection are harder to break: dictionary parsing now uses an SRG/TSRG member bridge, symbolic reflection is available at runtime, and reflection-heavy paths can bypass unsafe eager remaps when needed.
- Registry and security compatibility improved with broader mapped-signature detection, better freeze diagnostics, bootstrap-safe runtime config loading, and default config-scoped file permissions for manifest-only mods.
- Fabric Loader compatibility surface is wider, including version/dependency/contact/custom-value metadata that real Fabric mods query at runtime.
- Mixin hardening now includes accessor fallback rewriting plus safer resource lookup inside the InterMed mixin service.

### First Real-Run Notes

- Latest real launcher traces now reach the Forge client title-screen path with InterMed bridge activation, nested Fabric module discovery, pack injection, and Forge Mod List mirroring visible in logs.
- Remaining real-world blocker from the attached run: some Fabric mods still initialize too early for registry availability. The current observed example is `modonomicon`, which fails while resolving `minecraft:item` during background entrypoint init.
- External auth failures observed in TLauncher/Mojang verification (`401`) are not treated as an InterMed runtime success signal or a fixed compatibility issue in this alpha.

### Diagnostics

- Use the latest runtime jar when collecting bundles:

  ```bash
  java -cp InterMedCore-8.0.0-alpha.2.jar org.intermed.launcher.InterMedLauncher diagnostics-bundle \
    --game-dir /path/to/game \
    --mods-dir /path/to/game/intermed_mods
  ```

## v8.0.0-alpha.1

Open alpha for the frozen Minecraft `1.20.1` runtime path.

### Works In This Alpha

- Mixed-loader alpha runtime foundation for Fabric, Forge, and NeoForge mods.
- ClassLoader DAG isolation, dependency planning, runtime remapping, registry virtualization, Mixin conflict analysis, AOT cache, capability security hooks, diagnostics bundles, and curated API gap reports.
- Launcher-generated `launch-kit` files for JVM-args launchers and direct Linux/macOS/Windows Java wrappers.
- Public alpha gates for tests, coverage, strict security, runtime smoke/microbench/soak, and harness self-tests.

### Experimental / Needs Field Validation

- Public-mod Mixin stacks, conflict-heavy registry packs, real ResourceManager/DataPack reload behavior, custom payload networking, and mixed client/server sessions.
- Espresso and Wasm sandboxes beyond synthetic/runtime smoke coverage.
- Performance characteristics on real modpacks and long-running servers.

### Not Claimed

- No production stability guarantee.
- No `95%` public-mod compatibility claim.
- No general Minecraft `1.20+` claim.
- No field-proven hostile-mod security guarantee.
- No `10-15%` overhead claim for real modpacks.

### How To Report Issues

- Use GitHub issues with the closest template: crash/launch failure, compatibility gap, performance regression, or security hardening.
- Attach a diagnostics bundle whenever possible. The launcher writes one automatically on failed `launch`; it can also be generated manually:

  ```bash
  java -cp InterMedCore-8.0.0-alpha.1.jar org.intermed.launcher.InterMedLauncher diagnostics-bundle \
    --game-dir /path/to/game \
    --mods-dir /path/to/game/intermed_mods
  ```

- Follow [docs/alpha-triage.md](docs/alpha-triage.md) and [docs/known-limitations.md](docs/known-limitations.md) before filing broad compatibility claims.
