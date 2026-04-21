# Alpha Compatibility Proof - 2026-04-20

This note tracks the Phase 2 compatibility-proof work for the open alpha
snapshot. It is deliberately conservative: unexecuted work is marked as
`not-run`, not converted into a compatibility percentage.

## Current Status

- Harness bootstrap: completed after seeding the local Minecraft library cache
  with `trove:trove:1.0.2` from the local Gradle cache. The NeoForge installer
  otherwise received an empty artifact from the remote Maven endpoint and failed
  checksum validation.
- Harness discovery: completed with `./test-harness/run.sh discover --top=1000`.
- Cached corpus lock: `970` deduped runnable candidates from the requested top
  `1000`.
- Phase 2 proof-plan artifact: generated at
  `harness-output/report/alpha-compatibility-proof-plan.json` and mirrored to
  `build/launch-evidence/intermed-alpha-compatibility-proof-plan.json`.
- Current proof-plan accounting:
  - planned Phase 2 boot cases: `885`
  - existing executed boot cases linked from `results.json`: `184`
  - pass: `184`
  - fail: `0`
  - unsupported in this server-runnable harness lock: `0`
  - not-run: `701`
- Large local single-mod sweep: `not-run`. A `--mode=single --top=500
  --concurrency=4 --heap=2048` run was started, generated a `596` case plan, and
  was stopped because the machine entered severe memory pressure and the desktop
  OOM killer terminated VS Code.
- Large local pair sweep: `not-run`.
- Curated slice runtime sweep: `not-run`.
- Phase 3 / `--mode=full` popular-pack combos: `not-run` by request. The code is
  still present, but no current alpha evidence is claimed for it.

## Implemented Harness Support

- `--mode=slices` was added for fixed curated alpha slices. This is separate
  from `--mode=full`, so curated alpha proof does not accidentally execute
  Phase 3 pack-style combos.
- Curated slices currently include:
  - `fabric-foundation`
  - `forge-foundation`
  - `neoforge-minimal`
  - `data-resource-heavy`
  - `mixin-heavy-fabric`
  - `network-heavy-fabric`
- Harness runs now attempt to generate
  `harness-output/report/diagnostics-on-failure-<lane>.zip` when a run produces
  failing cases. If diagnostics generation itself fails, the harness records a
  log and keeps the classified test results.
- `./test-harness/run.sh alpha-proof --skip-discover --top=1000` now generates
  the conservative Phase 2 proof-plan without launching Minecraft server JVMs.

## Safe Low-Resource Continuation Plan

Use sharding rather than running hundreds of Minecraft servers in one local
session. On a 16 GiB desktop that is also running an IDE/browser, use one server
process at a time:

```bash
./test-harness/run.sh full --skip-bootstrap --skip-discover \
  --mode=single --top=500 --concurrency=1 --heap=768 \
  --shard-count=10 --shard-index=0 --retry-flaky
```

Repeat `--shard-index=0..9` for the single-mod lane. Then run pairs in shards:

```bash
./test-harness/run.sh full --skip-bootstrap --skip-discover \
  --mode=pairs --top=500 --pairs-top=35 \
  --concurrency=1 --heap=768 \
  --shard-count=12 --shard-index=0 \
  --resume-failed --retry-flaky
```

Repeat `--shard-index=0..11` for the pair lane. This plan keeps the final
`results.json` in one lane via `--resume-failed`, but avoids four simultaneous
2 GiB server JVMs.

Curated slices can be run separately and cheaply:

```bash
./test-harness/run.sh full --skip-bootstrap --skip-discover \
  --mode=slices --concurrency=1 --heap=768 --retry-flaky
```

## Reporting Rules

- Publish `pass`, `fail`, `unsupported`, and `not-run` accounting.
- Do not publish a compatibility percentage from these results.
- `PASS` means dedicated-server boot reached the harness success marker under
  permissive compatibility settings. It does not prove gameplay, multiplayer,
  strict security, or long-session stability.
- `FAIL_*` outcomes must retain their structured issue tags, e.g.
  `FAIL_MIXIN`, `FAIL_DEPENDENCY`, `FAIL_CAPABILITY`, `FAIL_CRASH`, or
  `FAIL_TIMEOUT`.
