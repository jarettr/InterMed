# Third-Party Notices

InterMed itself is released under the MIT License. The runtime and test harness
depend on third-party libraries that remain under their own upstream licenses.
Release artifacts also include a CycloneDX SBOM (`intermed-sbom.cdx.json`) with
the machine-readable dependency inventory used for alpha publication.

This notice is a human-readable guide, not a replacement for the upstream
license texts or the generated SBOM.

## Runtime And Build Dependencies

| Component | Purpose | License / notice |
| --- | --- | --- |
| Minecraft / Forge / Fabric / NeoForge APIs | Game and loader integration targets | Governed by their respective upstream project and Mojang/Microsoft terms |
| Architectury Loom / Gradle tooling | Development and packaging | Governed by upstream Gradle/plugin licenses |
| Byte Buddy / Byte Buddy Agent | Java agent and bytecode instrumentation | Apache License 2.0 |
| ASM | Bytecode analysis and generation | BSD-style ASM license |
| Tiny Remapper | Mapping/remapping support | Upstream FabricMC license terms |
| SpongePowered Mixin | Mixin compatibility surface | MIT License |
| MixinExtras | Extended Mixin annotation compatibility | MIT License |
| Gson | JSON parsing and report generation | Apache License 2.0 |
| SQLite JDBC | Local runtime/cache metadata support | Apache License 2.0 |
| GraalVM SDK / Polyglot Java | Espresso sandbox host integration | Universal Permissive License / upstream GraalVM terms |
| Chicory runtime / WASI / Wasm | WebAssembly sandbox runtime | Apache License 2.0 |
| JUnit Jupiter | Test execution | Eclipse Public License 2.0 |

## Redistribution Notes

- InterMed does not relicense third-party dependencies under MIT.
- Public alpha release payloads should include `LICENSE`, this notice file, and
  the generated SBOM so downstream users can audit the dependency set.
- If a downstream package bundles a modified dependency set, regenerate the SBOM
  and verify the relevant upstream license obligations before publishing it.
