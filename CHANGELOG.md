# Changelog

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
