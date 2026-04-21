# Alpha Sign-Off - 2026-04-19

This sign-off records the evidence generated for the `v8.0.0-alpha.1`
freeze branch. It is not a production release note and must not be read as a
field-compatibility or security guarantee.

## Source Evidence

- Release branch target: `release/v8.0.0-alpha.1`
- Source cleanup baseline: superseded by the final `v8.0.0-alpha.1` release commit
- Cleanup commit: `bf4f3b0 chore: ignore local codex scratch file`
- Release line: `v8.0.0-alpha.1`
- Runtime scope: Minecraft `1.20.1`
- Loader scope: Fabric / Forge / NeoForge alpha bridge scope
- Claim posture: open alpha only

## Source Hygiene

- `git status --short --untracked-files=all`: clean after cleanup
- `scripts/alpha_snapshot_audit.sh`: passed
- Generated logs, harness cache, JFR dumps, and metrics outputs are not staged
- Root scratch files `.codex`, `ModBootEvent.java`, and `RegistryFlushEvent.java`
  were removed from the worktree after confirming they were empty/duplicate
  scratch artifacts

## Mandatory Gates

The following gate passed before evidence generation:

```bash
./gradlew :app:strictSecurity :app:test :app:verifyRuntime --no-daemon
```

Result:

- Build successful
- `:app:strictSecurity` passed
- `:app:test` passed
- `:app:verifyRuntime` passed
- `:app:verifyRuntime` included compatibility smoke, registry microbench, and
  runtime soak lanes

The fat launcher artifacts were then rebuilt with:

```bash
./gradlew :app:coreJar :app:bootstrapJar --no-daemon
```

## Generated Evidence

Evidence was generated into `build/launch-evidence/`, which is intentionally
ignored by Git. The directory contains:

- `intermed-compatibility-corpus.json`
- `intermed-compatibility-sweep-matrix.json`
- `intermed-api-gap-matrix.json`
- `intermed-launch-readiness-report.json`
- `intermed-diagnostics-bundle.zip`
- `SHA256SUMS`

The diagnostics bundle contains 17 entries, including:

- `manifest.json`
- compatibility report
- compatibility corpus
- compatibility sweep matrix
- SBOM
- API gap matrix
- dependency plan
- security report
- runtime config
- launch-readiness report
- raw harness results
- startup, observability, microbench evidence artifacts

## Evidence Summary

Compatibility corpus summary:

- Total candidates: `1804`
- Parsed: `1664`
- Unsupported: `136`
- Failed metadata parse: `4`
- With mixins: `1162`
- With client resources: `1217`
- With server data: `663`
- Fabric candidates: `1234`
- Forge candidates: `423`
- NeoForge candidates: `7`

Compatibility sweep summary:

- Corpus total: `1804`
- Linked candidates: `141`
- Untested candidates: `1663`
- Harness results total: `184`
- Harness pass count: `184`
- Harness fail count: `0`
- Harness pass rate in provided results: `100.0`
- Average startup: `27799.125 ms`
- Unmatched harness results: `11`
- Candidate sweep statuses: `141 passed`, `1663 not-run`

API gap matrix summary:

- Total tracked symbols: `92`
- Present: `80`
- Missing: `12`
- Fabric: `56/59` present
- Forge: `16/16` present
- NeoForge: `8/17` present
- Alpha-stage symbols: `79/87` present
- Beta-stage symbols: `1/5` present

Launch-readiness summary:

- Required checks: `13`
- Present checks: `13`
- Missing checks: `0`
- Alpha evidence complete: `true`
- Harness results path linked:
  `harness-output/report/results.json`

## Evidence Checksums

```text
e35133b5a22b3dfedb6bd5348fa9f08d86e7222cfd9f18288fa9cdbec3663b14  build/launch-evidence/intermed-api-gap-matrix.json
48090d8ef1d7f8b1e6552d3006a6ae93d3c36327c596bc08d69406f03b45b429  build/launch-evidence/intermed-compatibility-corpus.json
d8e1c4f65df5385c8d96c136226a1227e5dc702c2d814bffd28c80af568ea7cc  build/launch-evidence/intermed-compatibility-sweep-matrix.json
89f5c08f078cda80051a48add17fa313b6c63a2cdee9a2b378ca4a82f2da2e9f  build/launch-evidence/intermed-diagnostics-bundle.zip
53b71ce0f9fac045aa23b9332e4e0b64dc649e11fe6872bad6b2a64c322035fe  build/launch-evidence/intermed-launch-readiness-report.json
```

## Important Non-Claims

- The `184/184` harness pass count is a result summary for the provided harness
  data only; it is not a public compatibility percentage.
- `1663` corpus candidates remain not-run in the linked sweep matrix.
- The launch-readiness report checks artifact presence and documentation
  guardrails; it does not run Minecraft.
- The diagnostics bundle command emitted a GraalVM sandbox probe warning for
  `__probe__`, but completed successfully.
- No production stability, hostile-mod security, multiplayer stability, or
  broad `1.20+` compatibility claim is made by this sign-off.

## Next Required Work

- Convert the evidence into a human-readable alpha release note.
- Decide which harness results are representative enough for alpha publication.
- Investigate the `11` unmatched harness results.
- Prioritize the `12` missing API-gap symbols, especially the `8` alpha-stage
  missing symbols.
- Add a real client/server multiplayer smoke lane before any beta language.
