# TLauncher Stress Test Guide

This is the fastest path for a live client-side smoke check on this machine after the automated harness runs.

## 1. Exact Java 21 path

Set this in TLauncher profile settings:

```text
/home/mak/.local/jdks/temurin21-full/jdk-21.0.10+7/bin/java
```

InterMed targets Java 21+. TLauncher may try to use an older runtime by default, so this path should be forced explicitly.

## 2. Exact JVM flags

Use these flags for a **Forge profile**:

```text
-javaagent:/home/mak/Projects/InterMed/app/build/libs/InterMedCore-8.0.0-alpha.1.jar --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
```

Use these flags for a **Fabric profile**:

```text
-javaagent:/home/mak/Projects/InterMed/app/build/libs/InterMedCore-8.0.0-alpha.1-fabric.jar --add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
```

## 3. Curated heavy packs

These are not “full modpacks from the internet”. They are curated local stress packs assembled from the already downloaded harness cache so you can reproduce heavy startup pressure quickly.

### `forge-stress`

Recommended profile:

```text
Forge 1.20.1-47.3.0
```

Representative mods:
- Create: https://modrinth.com/mod/create
- Quark: https://modrinth.com/mod/quark
- KubeJS: https://modrinth.com/mod/kubejs
- ModernFix: https://modrinth.com/mod/modernfix
- Architectury API: https://modrinth.com/mod/architectury-api

### `fabric-stress`

Recommended profile:

```text
Fabric 1.20.1
```

Representative mods:
- Fabric API: https://modrinth.com/mod/fabric-api
- Lithium: https://modrinth.com/mod/lithium
- Cobblemon: https://modrinth.com/mod/cobblemon
- C2ME: https://modrinth.com/mod/c2me-fabric
- Xaero's Minimap: https://modrinth.com/mod/xaeros-minimap

### `fabric-worldgen`

Recommended profile:

```text
Fabric 1.20.1
```

Representative mods:
- Terralith: https://modrinth.com/mod/terralith
- Biomes O' Plenty: https://modrinth.com/mod/biomes-o-plenty
- Lootr: https://modrinth.com/mod/lootr
- Dungeons & Taverns: https://modrinth.com/mod/dungeons-and-taverns
- Nature's Compass: https://modrinth.com/mod/natures-compass

## 4. One-command install

List available packs:

```bash
./scripts/install_tlauncher_stress_pack.sh list
```

Install all curated packs into separate game directories:

```bash
./scripts/install_tlauncher_stress_pack.sh install-all
```

That creates:

```text
~/.minecraft-intermed-packs/forge-stress
~/.minecraft-intermed-packs/fabric-stress
~/.minecraft-intermed-packs/fabric-worldgen
```

Each directory contains:
- `intermed_mods/` with the installed stress pack that InterMed should ingest
- `mods/` intentionally kept empty so native loaders do not pre-consume mixed-loader jars
- `README-INTERMED.txt`
- `intermed-jvm-args.txt`
- `intermed-java21-path.txt`
- `intermed-pack-manifest.json`

## 5. TLauncher setup

For each stress pack:

1. Create a separate launcher profile.
2. Set the correct loader/version:
   - `Forge 1.20.1-47.3.0` for `forge-stress`
   - `Fabric 1.20.1` for `fabric-stress` and `fabric-worldgen`
3. Set the **Game directory** to the corresponding folder under `~/.minecraft-intermed-packs/`.
4. Set **Java path** to Java 21.
5. Paste the exact JVM args from `intermed-jvm-args.txt`.

Important:
- do not point these stress profiles at the shared `~/.minecraft` directory
- do not copy mixed Fabric/Forge jars into native `mods/`
- for InterMed validation, the pack should live in `intermed_mods/`, which is already handled by the installer

## 6. Recommended live test order

Run in this order:

1. `fabric-stress`
2. `forge-stress`
3. `fabric-worldgen`

This gives:
- a fast Fabric systems/API check
- a heavier Forge compatibility check
- a worldgen/data-heavy Fabric check

## 7. TLauncher helper

TLauncher is already available on this machine here:

```text
/home/mak/Apps/TLauncher/TLauncher.jar
```

And this launcher helper already forces Java 21:

```text
/home/mak/Apps/TLauncher/run-tlauncher-java21.sh
```
