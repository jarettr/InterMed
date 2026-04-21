#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
REGISTRY_JSON="${REPO_ROOT}/harness-output/cache/mods/registry.json"
MOD_CACHE_DIR="${REPO_ROOT}/harness-output/cache/mods/jars"
JAVA21_BIN="/home/mak/.local/jdks/temurin21-full/jdk-21.0.10+7/bin/java"
FORGE_AGENT="${REPO_ROOT}/app/build/libs/InterMedCore-8.0.0-alpha.1.jar"
FABRIC_AGENT="${REPO_ROOT}/app/build/libs/InterMedCore-8.0.0-alpha.1-fabric.jar"
DEFAULT_BASE_DIR="${HOME}/.minecraft-intermed-packs"

usage() {
  cat <<'EOF'
Usage:
  scripts/install_tlauncher_stress_pack.sh list
  scripts/install_tlauncher_stress_pack.sh install <pack-name> [target-dir]
  scripts/install_tlauncher_stress_pack.sh install-all [base-dir]

Pack names:
  forge-stress
  forge-mega
  forge-connector-hell
  fabric-stress
  fabric-mega
  fabric-worldgen

Examples:
  scripts/install_tlauncher_stress_pack.sh list
  scripts/install_tlauncher_stress_pack.sh install forge-stress ~/.minecraft-intermed-packs/forge-stress
  scripts/install_tlauncher_stress_pack.sh install-all
EOF
}

require_inputs() {
  if [[ ! -f "${REGISTRY_JSON}" ]]; then
    echo "[ERROR] Missing registry cache: ${REGISTRY_JSON}" >&2
    echo "        Run ./test-harness/run.sh discover --top=100 first." >&2
    exit 1
  fi
  if [[ ! -d "${MOD_CACHE_DIR}" ]]; then
    echo "[ERROR] Missing mod cache: ${MOD_CACHE_DIR}" >&2
    echo "        Run ./test-harness/run.sh discover --top=100 first." >&2
    exit 1
  fi
}

write_pack() {
  local pack_name="$1"
  local target_dir="$2"

  mkdir -p "${target_dir}"
  PACK_NAME="${pack_name}" TARGET_DIR="${target_dir}" REGISTRY_JSON="${REGISTRY_JSON}" MOD_CACHE_DIR="${MOD_CACHE_DIR}" \
  JAVA21_BIN="${JAVA21_BIN}" FORGE_AGENT="${FORGE_AGENT}" FABRIC_AGENT="${FABRIC_AGENT}" \
  python3 - <<'PY'
import json
import os
import shutil
from pathlib import Path

pack_name = os.environ["PACK_NAME"]
target_dir = Path(os.environ["TARGET_DIR"]).expanduser()
registry_path = Path(os.environ["REGISTRY_JSON"])
cache_dir = Path(os.environ["MOD_CACHE_DIR"])
java21 = os.environ["JAVA21_BIN"]
forge_agent = os.environ["FORGE_AGENT"]
fabric_agent = os.environ["FABRIC_AGENT"]

packs = {
    "forge-stress": {
        "loader": "forge",
        "minecraft": "1.20.1",
        "profile_hint": "Forge 1.20.1-47.3.0",
        "agent": forge_agent,
        "mods": [
            "cloth-config", "ferrite-core", "yacl", "architectury-api", "modernfix",
            "jei", "jade", "geckolib", "collective", "create", "farmers-delight",
            "kubejs", "rhino", "quark", "travelersbackpack", "waystones",
            "polymorph", "connector", "forgified-fabric-api", "resourceful-config",
            "curios",
        ],
        "links": [
            "https://modrinth.com/mod/create",
            "https://modrinth.com/mod/quark",
            "https://modrinth.com/mod/kubejs",
            "https://modrinth.com/mod/modernfix",
            "https://modrinth.com/mod/architectury-api",
        ],
    },
    "forge-mega": {
        "loader": "forge",
        "minecraft": "1.20.1",
        "profile_hint": "Forge 1.20.1-47.3.0",
        "agent": forge_agent,
        "mods": [
            "cloth-config", "ferrite-core", "yacl", "architectury-api", "modernfix",
            "jei", "jade", "geckolib", "collective", "create", "farmers-delight",
            "kubejs", "rhino", "quark", "travelersbackpack", "waystones",
            "polymorph", "connector", "connector-extras", "forgified-fabric-api",
            "resourceful-config", "curios", "memoryleakfix", "creativecore",
            "fancymenu", "comforts", "patchouli", "emi", "midnightlib",
            "shulkerboxtooltip", "timeless-and-classics-zero", "mru", "better-combat",
        ],
        "links": [
            "https://modrinth.com/mod/create",
            "https://modrinth.com/mod/kubejs",
            "https://modrinth.com/mod/quark",
            "https://modrinth.com/mod/connector",
            "https://modrinth.com/mod/forgified-fabric-api",
        ],
    },
    "forge-connector-hell": {
        "loader": "forge",
        "minecraft": "1.20.1",
        "profile_hint": "Forge 1.20.1-47.3.0",
        "agent": forge_agent,
        "mods": [
            "connector", "connector-extras", "forgified-fabric-api", "cloth-config",
            "architectury-api", "yacl", "modernfix", "jei", "jade", "geckolib",
            "collective", "create", "farmers-delight", "kubejs", "rhino",
            "resourceful-config", "ferrite-core", "curios", "memoryleakfix",
            "creativecore", "patchouli", "better-combat",
        ],
        "links": [
            "https://modrinth.com/mod/connector",
            "https://modrinth.com/mod/connector-extras",
            "https://modrinth.com/mod/forgified-fabric-api",
            "https://modrinth.com/mod/create",
            "https://modrinth.com/mod/kubejs",
        ],
    },
    "fabric-stress": {
        "loader": "fabric",
        "minecraft": "1.20.1",
        "profile_hint": "Fabric 1.20.1",
        "agent": fabric_agent,
        "mods": [
            "fabric-api", "lithium", "fabric-language-kotlin", "xaeros-minimap",
            "xaeros-world-map", "appleskin", "collective", "forge-config-api-port",
            "konkrete", "puzzles-lib", "owo-lib", "resourceful-lib", "cobblemon",
            "c2me-fabric", "debugify", "moonlight", "clumps", "trinkets",
            "starlight", "controlify",
        ],
        "links": [
            "https://modrinth.com/mod/fabric-api",
            "https://modrinth.com/mod/lithium",
            "https://modrinth.com/mod/cobblemon",
            "https://modrinth.com/mod/c2me-fabric",
            "https://modrinth.com/mod/xaeros-minimap",
        ],
    },
    "fabric-mega": {
        "loader": "fabric",
        "minecraft": "1.20.1",
        "profile_hint": "Fabric 1.20.1",
        "agent": fabric_agent,
        "mods": [
            "fabric-api", "lithium", "fabric-language-kotlin", "xaeros-minimap",
            "xaeros-world-map", "appleskin", "collective", "forge-config-api-port",
            "konkrete", "puzzles-lib", "owo-lib", "resourceful-lib", "cobblemon",
            "c2me-fabric", "debugify", "moonlight", "clumps", "trinkets",
            "starlight", "controlify", "supplementaries", "visual-workbench",
            "natures-compass", "terralith", "lootr", "dungeons-and-taverns",
            "yungs-api", "yungs-better-mineshafts", "yungs-better-strongholds",
            "yungs-better-end-island", "yungs-better-witch-huts", "biomes-o-plenty",
        ],
        "links": [
            "https://modrinth.com/mod/fabric-api",
            "https://modrinth.com/mod/cobblemon",
            "https://modrinth.com/mod/c2me-fabric",
            "https://modrinth.com/mod/terralith",
            "https://modrinth.com/mod/biomes-o-plenty",
        ],
    },
    "fabric-worldgen": {
        "loader": "fabric",
        "minecraft": "1.20.1",
        "profile_hint": "Fabric 1.20.1",
        "agent": fabric_agent,
        "mods": [
            "fabric-api", "terralith", "yungs-api", "yungs-better-nether-fortresses",
            "yungs-better-ocean-monuments", "yungs-better-jungle-temples",
            "yungs-better-mineshafts", "yungs-better-strongholds",
            "yungs-better-witch-huts", "yungs-better-end-island",
            "dungeons-and-taverns", "lootr", "biomes-o-plenty", "natures-compass",
            "visual-workbench", "supplementaries",
        ],
        "links": [
            "https://modrinth.com/mod/terralith",
            "https://modrinth.com/mod/biomes-o-plenty",
            "https://modrinth.com/mod/lootr",
            "https://modrinth.com/mod/dungeons-and-taverns",
            "https://modrinth.com/mod/natures-compass",
        ],
    },
}

if pack_name not in packs:
    raise SystemExit(f"[ERROR] Unknown pack: {pack_name}")

pack = packs[pack_name]
registry = json.loads(registry_path.read_text())
by_slug = {item["slug"]: item for item in registry}

missing = [slug for slug in pack["mods"] if slug not in by_slug]
if missing:
    raise SystemExit(
        "[ERROR] Missing mod metadata in registry for: " + ", ".join(missing) +
        "\n        Re-run ./test-harness/run.sh discover --top=100"
    )

mods_dir = target_dir / "intermed_mods"
native_mods_dir = target_dir / "mods"
config_dir = target_dir / "config"
mods_dir.mkdir(parents=True, exist_ok=True)
native_mods_dir.mkdir(parents=True, exist_ok=True)
config_dir.mkdir(parents=True, exist_ok=True)

for existing in mods_dir.iterdir():
    if existing.is_symlink() or existing.is_file():
        existing.unlink()
    elif existing.is_dir():
        shutil.rmtree(existing)

for existing in native_mods_dir.iterdir():
    if existing.is_symlink() or existing.is_file():
        existing.unlink()
    elif existing.is_dir():
        shutil.rmtree(existing)

selected = []
for slug in pack["mods"]:
    item = by_slug[slug]
    jar_name = item["fileName"]
    source = cache_dir / jar_name
    if not source.exists():
        raise SystemExit(
            f"[ERROR] Missing cached jar for {slug}: {source}\n"
            "        Re-run ./test-harness/run.sh discover --top=100"
        )
    dest = mods_dir / jar_name
    try:
        dest.symlink_to(source)
    except OSError:
        shutil.copy2(source, dest)
    selected.append(item)

jvm_args = [
    f"-javaagent:{pack['agent']}",
    "--add-opens=java.base/java.lang=ALL-UNNAMED",
    "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
    f"-Druntime.game.dir={target_dir}",
    f"-Dintermed.modsDir={mods_dir}",
]

(target_dir / "intermed-jvm-args.txt").write_text("\n".join(jvm_args) + "\n")
(target_dir / "intermed-java21-path.txt").write_text(java21 + "\n")
(native_mods_dir / "README-INTERMED.txt").write_text(
    "Keep this directory empty for InterMed mixed-loader validation.\n"
    "Place mixed Fabric/Forge/NeoForge packs in ../intermed_mods.\n"
)

manifest = {
    "pack": pack_name,
    "loader": pack["loader"],
    "minecraft": pack["minecraft"],
    "profileHint": pack["profile_hint"],
    "java21": java21,
    "agent": pack["agent"],
    "modCount": len(selected),
    "mods": [
        {
            "slug": item["slug"],
            "name": item["name"],
            "version": item["versionNumber"],
            "file": item["fileName"],
            "downloads": item["downloads"],
            "url": f"https://modrinth.com/mod/{item['slug']}",
        }
        for item in selected
    ],
    "notableLinks": pack["links"],
}
(target_dir / "intermed-pack-manifest.json").write_text(
    json.dumps(manifest, indent=2, ensure_ascii=False) + "\n"
)

readme = f"""InterMed TLauncher stress pack: {pack_name}

Recommended launcher profile:
  {pack['profile_hint']}

Game directory:
  {target_dir}

InterMed mods directory:
  {mods_dir}

Java 21 path:
  {java21}

JVM args for TLauncher:
  {' '.join(jvm_args)}

Representative links:
""" + "\n".join(f"  - {link}" for link in pack["links"]) + "\n"

(target_dir / "README-INTERMED.txt").write_text(readme)

print(f"[Pack] Installed {pack_name} -> {target_dir}")
print(f"[Pack] Loader      : {pack['loader']}")
print(f"[Pack] MC version  : {pack['minecraft']}")
print(f"[Pack] Mods        : {len(selected)}")
print(f"[Pack] Java 21     : {java21}")
print(f"[Pack] JVM args    : {' '.join(jvm_args)}")
PY
}

install_all() {
  local base_dir="${1:-${DEFAULT_BASE_DIR}}"
  mkdir -p "${base_dir}"
  write_pack "fabric-stress" "${base_dir}/fabric-stress"
  write_pack "fabric-mega" "${base_dir}/fabric-mega"
  write_pack "fabric-worldgen" "${base_dir}/fabric-worldgen"
  write_pack "forge-stress" "${base_dir}/forge-stress"
  write_pack "forge-mega" "${base_dir}/forge-mega"
  write_pack "forge-connector-hell" "${base_dir}/forge-connector-hell"
}

list_packs() {
  cat <<EOF
Available packs:
  forge-stress     Heavy Forge stack: Create, KubeJS, Quark, JEI, Jade, ModernFix, Architectury, Connector
  forge-mega       Larger Forge stack with Create/KubeJS/Quark/Connector and extra content/system mods
  forge-connector-hell  Experimental Forge+Sinytra Connector stack for “should survive by design” testing
  fabric-stress    Heavy Fabric stack: Fabric API, Lithium, Kotlin, Xaero stack, Cobblemon, C2ME, Starlight
  fabric-mega      Larger Fabric stack with Cobblemon, C2ME, worldgen, utility, map and API layers
  fabric-worldgen  Fabric worldgen/content stack: Terralith, YUNG's suite, Lootr, Dungeons & Taverns, BOP

Default install base:
  ${DEFAULT_BASE_DIR}

Java 21:
  ${JAVA21_BIN}
EOF
}

main() {
  require_inputs
  local cmd="${1:-}"
  case "${cmd}" in
    list)
      list_packs
      ;;
    install)
      local pack_name="${2:-}"
      local target_dir="${3:-}"
      if [[ -z "${pack_name}" ]]; then
        usage
        exit 1
      fi
      if [[ -z "${target_dir}" ]]; then
        target_dir="${DEFAULT_BASE_DIR}/${pack_name}"
      fi
      write_pack "${pack_name}" "${target_dir}"
      ;;
    install-all)
      install_all "${2:-${DEFAULT_BASE_DIR}}"
      ;;
    *)
      usage
      exit 1
      ;;
  esac
}

main "$@"
