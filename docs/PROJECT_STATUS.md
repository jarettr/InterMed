# Project status

InterMed 0.1.8-alpha is an alpha static analyzer with an operational
Compatibility Lab. The CLI, report schemas, corpus locks, resumable campaigns,
and bounded runtime-observation path are usable; compatibility is not promised
for every Minecraft or loader release and machine-facing formats may still
change before 1.0.

## 0.1.8 real-pack measurement gate

The 2026-08-19 release gate analyzed 12 materialized Modrinth packs spanning
Fabric, Forge, and NeoForge from Minecraft 1.12.2 through 1.21.1. Every case was
pinned by an `intermed-corpus-lock-v2` generated from its authoritative
`.mrpack`. Before Doctor ran, Layer K verified all 27,557 locked files (6.86 GiB)
against their declared content hashes; no required or optional file was absent.

All 12 reports were produced by the same 0.1.8-alpha executable and effective
rule set (analyzer fingerprint
`1608e28c4a72276e9c5a15da0df38c4ddb95dcbbe2ee3c94273a7245311e368e`,
executable SHA-256
`70f197c01077099318b58a994b211ce4fc8ed7cade1ad1feea1175a7304f6c1a`).
The campaign completed with zero infrastructure failures, harness failures,
unfinished cases, or Doctor operational errors.

| Pack | Loader / Minecraft | Locked files | Findings (Error / Warn) | Confirmed / review / incomplete | Facts generated / retained / compacted | Doctor time | Peak RSS |
|---|---|---:|---:|---:|---:|---:|---:|
| Better MC Fabric BMC2 | Fabric / 1.20.1 | 4,949 | 2,580 (4 / 53) | 4 / 29 / 3 | 332,682 / 43,189 / 289,493 | 44.0 s | 1,401 MiB |
| Better MC Fabric BMC3 | Fabric / 1.21.1 | 1,157 | 2,194 (2 / 26) | 2 / 26 / 0 | 255,910 / 36,180 / 219,730 | 41.8 s | 1,155 MiB |
| Better MC Forge BMC4 | Forge / 1.20.1 | 4,985 | 2,554 (8 / 98) | 8 / 49 / 21 | 376,064 / 36,156 / 339,908 | 55.6 s | 1,816 MiB |
| Cave Horror | Forge / 1.20.1 | 6,235 | 909 (1 / 12) | 1 / 10 / 2 | 185,354 / 19,926 / 165,428 | 22.3 s | 1,354 MiB |
| Cobblemon Fabric | Fabric / 1.21.1 | 2,502 | 620 (0 / 3) | 0 / 3 / 0 | 60,951 / 18,007 / 42,944 | 39.4 s | 1,132 MiB |
| Cobblemon NeoForge | NeoForge / 1.21.1 | 2,505 | 538 (0 / 11) | 0 / 9 / 2 | 58,068 / 16,413 / 41,655 | 38.2 s | 1,104 MiB |
| Create+ | Forge / 1.19.2 | 1,321 | 1,830 (1 / 36) | 1 / 16 / 20 | 211,858 / 31,021 / 180,837 | 24.6 s | 1,454 MiB |
| FOM | NeoForge / 1.21.1 | 491 | 1,008 (0 / 20) | 0 / 19 / 1 | 95,616 / 21,692 / 73,924 | 28.2 s | 1,272 MiB |
| Parasites Reloaded | Forge / 1.12.2 | 350 | 52 (0 / 0) | 0 / 0 / 0 | 26,942 / 26,942 / 0 | 21.7 s | 542 MiB |
| Prominence II | Fabric / 1.20.1 | 2,163 | 3,615 (1 / 65) | 1 / 44 / 10 | 611,650 / 59,754 / 551,896 | 117.7 s | 2,540 MiB |
| Slimes Adventure | Fabric / 1.21.1 | 326 | 903 (0 / 11) | 0 / 6 / 2 | 264,070 / 20,446 / 243,624 | 20.7 s | 1,146 MiB |
| Pixelmon | NeoForge / 1.21.1 | 573 | 70 (0 / 3) | 0 / 2 / 2 | 85,507 / 10,777 / 74,730 | 90.7 s | 613 MiB |
| **Total / maximum** | 12 reports | **27,557** | **16,873 (17 / 338)** | **17 / 213 / 63** | **2,564,672 / 340,503 / 2,224,169** | **545.0 s** | **2,540 MiB** |

The 17 hard conclusions were manually inspected in their reports and retained
facts. They comprise seven absent required providers, two exact version
conflicts, seven loader mismatches without a compatible runtime bridge, and one
duplicate active mod ID. Every one is `asserted` and `confirmed` with no
assessment blocker. Better MC Forge BMC4 contains six required Fabric artifacts
but no Sinytra Connector runtime artifact; Connector Extras and Forgified Fabric
API alone do not establish classloading or runtime compatibility. Cave Horror
contains a Fabric GeckoLib 3 build for Minecraft 1.16.5 in an authoritative
Forge 1.20.1 pack with no bridge. Conversely, Cobblemon NeoForge does contain a
runtime Connector bridge, so its two cross-loader cases remain abstained review
items rather than hard errors. Descriptorless KotlinForForge and
ConfiguredDefaults language-provider containers are now identified without
spurious unknown-source findings.

Mixin analysis was explicitly active at `basic` depth in all 12 packs. No
Minecraft jar or compatible mappings were supplied, and every report records
those capabilities as unavailable, so Minecraft class or method absence is not
promoted to proof. Pixelmon's resource-AST collector is incomplete for a
different, legitimate reason: multiple language JSON files exceed the configured
1 MiB relevant-entry limit. Oversized unrelated media does not affect metadata
completeness.

The campaign retained 340,503 of 2,564,672 generated facts after all registered
rules completed. The 2,224,169 compacted facts are snapshot detail, not
collection-time loss; operational and incomplete-coverage records remain
visible. The campaign artifacts include per-case target verification, Doctor
JSON/profile output, observations, persistent state, aggregate JSON, static HTML,
and semantic finding clusters.

Against the preliminary measurement recorded on the same locked corpus, default
Warn output fell from 2,466 to 338 and total findings from 20,651 to 16,873.
The reduction came from corrected Mixin triage, provider/container identity, and
default-visibility policy; the increase from 11 to 17 hard conclusions is the
result of distinguishing a lone authoritative foreign descriptor from a
bridge-ambiguous mixed-loader artifact.

## Runtime-observation gate

A separate synthetic regression uses a multiline Mixin wrapper whose deepest
cause is `java.lang.OutOfMemoryError: Java heap space` in
`examplemod.Core.tick`. `lab capture` and Doctor both normalize it to one
terminal incident, select the deepest throwable rather than the wrapper, retain
the physical occurrence identity, and classify it as `out-of-memory`. This is a
parser/correlation fixture, not evidence that any of the 12 real packs was
launched.

## Supported use

- Static inspection of local servers, launcher instances, mods directories,
  `.mrpack`/zip packs, logs, and crash reports.
- Metadata, dependency, resource, mixin, script, security-preflight, SBOM, and
  imported Spark analysis at the documented depth.
- Terminal, JSON, SARIF, and self-contained HTML reports, with operational
  failures kept separate from domain findings.
- Content-addressed `.mrpack` locks and materialization, target verification,
  resumable bounded-parallel Layer-K campaigns, captured runtime observations,
  explicit sandboxed command plans, accuracy reports, and mismatch clustering.
- Bounded archive reads, a persistent scan cache, and a configurable worker cap
  for large packs.

## Not promised by this alpha

- `doctor` never launches Minecraft. Layer-K execution requires an explicit
  command and sandbox policy; loader installation and network acquisition are
  intentionally separate inputs.
- A static-only campaign does not produce runtime precision/recall. Runtime
  absence can refute a prediction only when the required milestone and coverage
  were actually observed.
- Security output is a preflight of signatures, identity, and sensitive API
  references; it is not malware certification or full behavioral analysis.
- Mixin apply absence is conclusive only when the relevant complete classpath and
  compatible namespace/mappings are available.
- No minimum Minecraft or loader version has been declared. Older and unusual
  metadata dialects remain an explicit compatibility frontier.
- InterMed never edits the analyzed pack. Overlay and fix operations are previews
  or writes to a separately requested output location.
- Telemetry is disabled by default. There is no background sender, default
  endpoint, stable installation identifier, or implicit log upload.

The [analysis reference](reference/analysis.md) gives the exact stopping point of
each analyzer. The [roadmap](ROADMAP.md) tracks the remaining acquisition,
runtime coverage, measurement-corpus, evidence-to-action, and product work.
