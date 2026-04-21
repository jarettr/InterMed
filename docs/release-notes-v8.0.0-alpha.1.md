# InterMed v8.0.0-alpha.1 Release Notes

InterMed `v8.0.0-alpha.1` is the first open alpha for the frozen Minecraft
`1.20.1` runtime path. It is meant for testers, mod developers, and server
operators who are comfortable collecting diagnostics and reporting alpha bugs.
It is not a stable release for production servers or ordinary modpack users yet.

## What Works

- Fabric, Forge, and NeoForge are in the alpha scope for Minecraft `1.20.1`.
- The runtime path includes discovery, dependency planning, DAG classloaders,
  bytecode remapping, registry virtualization, Mixin analysis with AOT cache,
  capability security hooks, diagnostics bundles, compatibility reports, SBOM
  generation, and curated API gap reporting.
- `InterMedLauncher launch-kit` can generate JVM argument snippets and direct
  Linux/macOS/Windows wrappers from the release jars.
- The public alpha gate covers app tests, alpha coverage, strict security,
  compatibility smoke, registry/remapper/event hot-path microbenchmarks,
  runtime soak, and test-harness self-tests.

## Experimental Areas

- Public-mod Mixin stacks and unusual Mixin features.
- Real ResourceManager/DataPack reload behavior and conflict-heavy VFS cases.
- Real multiplayer handshake, registry sync, and custom payload exchange.
- Espresso and Wasm sandbox execution beyond synthetic scenarios.
- Long-running modpack performance and memory behavior.

## Explicit Non-Claims

- No production stability claim.
- No `95%` public-mod compatibility claim.
- No general Minecraft `1.20+` compatibility claim.
- No field-proven hostile-mod security claim.
- No `10-15%` real-modpack overhead claim.
- No full Fabric API, Forge API, or NeoForge API parity claim.

## How To Install

1. Download the release payload from GitHub Releases.
2. Use Java 21.
3. Generate a launch kit:

   ```bash
   java -jar InterMedCore-8.0.0-alpha.1.jar launch-kit --game-dir /path/to/.minecraft
   ```

4. Put alpha-test mods in `intermed_mods/`.
5. Use the generated launcher JVM args snippet or direct wrapper script.

See [docs/user-guide.md](user-guide.md) for the full walkthrough.

## How To Report Issues

- Use the closest GitHub issue template.
- Attach a diagnostics bundle when possible.
- If a launch command fails through `InterMedLauncher launch`, a diagnostics
  bundle is written automatically unless disabled.
- Manual diagnostics bundle:

  ```bash
  java -cp InterMedCore-8.0.0-alpha.1.jar org.intermed.launcher.InterMedLauncher diagnostics-bundle \
    --game-dir /path/to/game \
    --mods-dir /path/to/game/intermed_mods
  ```

Read [docs/alpha-triage.md](alpha-triage.md) and
[docs/known-limitations.md](known-limitations.md) before filing broad
compatibility or security claims.
