# Project status

InterMed 0.1.9-alpha is an alpha static analyzer with an operational
Compatibility Lab. The CLI, report schemas, corpus locks, resumable campaigns,
and bounded runtime-observation path are usable; compatibility is not promised
for every Minecraft or loader release and machine-facing formats may still
change before 1.0.

## 0.1.9 real-pack measurement gate

The 2026-08-29 release gate analyzed 101 fully materialized Modrinth packs:
77 Fabric, 18 Forge, 4 NeoForge, and 2 Quilt instances spanning Minecraft
1.7.10 through 26.2. Every case was pinned by an `intermed-corpus-lock-v2`
generated from its authoritative `.mrpack`. Before Doctor ran, Layer K verified
all 56,448 locked files (23,844,535,832 bytes) against their declared content
hashes; no required or optional file was absent.

All 101 reports were produced by one 0.1.9-alpha executable (SHA-256
`055d0c8589f4168498daab2d96d09d02cf33f63245eb5103c898dd74d0e67218`)
and one effective rule pack (SHA-256
`3e7a297cb2a4f9d4aa31541ba4c64394444d3db47f79709a752c73a6e1c9570a`).
The campaign completed with zero infrastructure failures, harness failures,
unfinished cases, target-verification failures, or Doctor operational errors.

| Measurement | Result |
|---|---:|
| Materialized packs / reports | 101 / 101 |
| Locked files verified | 56,448 |
| Bytes re-hashed before analysis | 23,844,535,832 |
| Raw findings | 62,356 |
| Confirmed problems | 18 |
| Needs review | 595 |
| Incomplete analysis | 51 |
| Facts generated / retained / compacted | 5,538,175 / 1,902,546 / 3,635,629 |
| Aggregate Doctor time | 510.501 s |
| Maximum per-run peak RSS | 2,502,066,176 bytes (2.33 GiB) |
| Final cache payload size / files | 417,903,038 bytes / 37,186 |

The 18 hard conclusions were manually inspected against their report evidence
and materialized inputs. They comprise seven duplicate active mod IDs, six exact
version/incompatibility conclusions, two absent required providers, and three
terminal Forge mod-loading incidents recovered from supplied crash reports.
Every one is `asserted` and `confirmed`; no hard conclusion carries an
assessment blocker. Runtime log context is not treated as culprit attribution:
only terminal incident evidence produces the three runtime Errors, while raw
signals and simple mod mentions remain explanation/context detail.

The 51 incomplete items are intentional and attributable. Forty-nine are
visible structured abstentions: 41 resource conclusions gated by possible
runtime mutation and eight loader-mismatch conclusions gated by bridge
uncertainty. The remaining two are collector-level gaps for one Ardacraft input
whose relevant resource entries exceed configured bounds. Oversized unrelated
media does not affect metadata completeness.

Mixin analysis was explicitly active at `basic` depth in all 101 packs. No
Minecraft jar or compatible mappings were supplied, and every report records
those capabilities as unavailable, so Minecraft class or method absence is not
promoted to proof. Repetitive recipe and resource conflicts are represented by
typed writer-pair clusters on the default surface; individual resources remain
available as explain-only records. Within every report, occurrence IDs are
unique and semantically different payloads are never silently merged.

The campaign retained 1,902,546 of 5,538,175 generated facts after all
registered rules completed. The 3,635,629 compacted facts are snapshot detail,
not collection-time loss. A cold 512 MiB cache stayed within its logical payload
budget during the full campaign and ended at 417,903,038 bytes; filesystem block
usage is higher because the cache contains many small fingerprint and payload
files.

## Runtime-observation gate

One corpus pack includes historical Forge crash reports and logs. Doctor recovers
the target Java 17 / Forge 47.3.1 / Minecraft 1.20.1 environment independently
of the analyzer host, parses three `-- MOD ... --` failure sections, and emits
separate terminal incidents for `car`, `framework`, and `toolbelt`. The generic
`Mod Loading has failed` wrapper remains context and does not displace the
specific causes.

Synthetic regression fixtures additionally cover multiline/flattened event
equivalence, repeated physical incidents, background ERROR recovery, watchdog
and OOM terminality, runtime/static contradiction handling, provider uncertainty,
foreign descriptor selection, bridge ambiguity, cache replacement with preserved
size/mtime, bounded class scanning, and VFS second-pass read failure.

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
- Bounded archive reads, a persistent content-verified scan cache, and a
  configurable worker cap for large packs.

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
