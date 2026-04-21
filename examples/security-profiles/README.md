# Security Profile Examples

These files are examples for `config/intermed-security-profiles.json`.
Copy the relevant object into that file and replace `modId`, paths, and hosts
with the real mod values before enabling it on a server.

## Ordinary Mod

`ordinary-mod.json` grants only scoped config reads. This fits mods that read
their own config and do not need network, writes, native libraries, process
spawning, private reflection, or low-level memory access.

## Network/File/Config-Heavy Mod

`network-file-config-heavy-mod.json` grants scoped config/cache writes and a
small host allowlist. It is intended for mods that sync metadata, download a
known catalog, or maintain a local cache.

Do not add `PROCESS_SPAWN`, `NATIVE_LIBRARY`, `REFLECTION_ACCESS`, or
`MEMORY_ACCESS` unless the mod is trusted and the need is understood.
