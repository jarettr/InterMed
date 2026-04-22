# InterMed Beta-Prep Testing

This directory contains thin beta-prep orchestration wrappers. They sit on top
of the existing Gradle tasks, launcher CLI, and compatibility harness. They do
not replace those entrypoints.

## Output Contract

All wrappers write case artifacts under:

`build/test-runs/<YYYYMMDD>/<suite>/<case-id>/`

Every case directory should contain:

- `run-manifest.json`
- `environment.json`
- `command.txt`
- `exit-status.json`
- `stdout.log`
- `stderr.log`
- `mod-list.json`
- `result.json`

Each suite writes:

- `suite-summary.json`

The dashboard generator writes:

- `beta-readiness-dashboard.json`

## Main Commands

```bash
testing/run_local_gates.sh
testing/run_smoke_suite.sh
testing/run_security_suite.sh
testing/run_client_server_smoke.sh
testing/run_corpus_shard.sh
testing/run_perf_baseline.sh
testing/run_soak.sh
testing/import_gradle_evidence.py
testing/import_harness_boot_evidence.py
testing/promote_results.py
```

## Lightweight Validation

These checks do not run heavy Minecraft or Gradle lanes:

```bash
python3 -m json.tool testing/test-cases.json >/tmp/intermed-test-cases.json
python3 -m json.tool testing/frozen-beta-candidate.json >/tmp/intermed-frozen-beta-candidate.json
python3 -m json.tool testing/waivers.json >/tmp/intermed-waivers.json
python3 -m py_compile testing/_case_runner.py testing/import_gradle_evidence.py testing/import_harness_boot_evidence.py testing/promote_results.py
bash -n testing/*.sh
```

## Import Existing Gradle Evidence

After running Gradle/JUnit gates, import their XML reports into beta-case
artifact directories without rerunning heavy work:

```bash
testing/import_gradle_evidence.py
```

This is deliberately conservative. It can mark synthetic Gradle-backed cases
such as DAG, remap, registry, selected network bridge paths, VFS policy/reload
fixtures, strict security, sandbox, and TCCL fixtures as `pass` when their JUnit
reports are green. Synthetic network and VFS imports are labelled as such in
their evidence artifacts; they do not claim visual client coverage or a human
play session.

It does not convert unit tests into real dedicated server boot, real client menu
entry, real 3-to-5 minute play-session smoke, native fixture, external corpus,
or medium soak evidence.

Import real harness boot evidence from an existing `results-booted.json`:

```bash
testing/import_harness_boot_evidence.py \
  --results build/test-runs/20260422/nightly-corpus-real-small/CORPUS-001/harness-output/report/results-booted.json
```

This can promote passing harness dedicated-server boots into `BOOT-001`,
`BOOT-002`, or `BOOT-003` depending on the loader family present in the harness
results. It still does not prove mixed-loader boot, client/server login, or
datapack reload.

## Honest Evidence Rules

Some wrappers intentionally emit `not-run` or `blocked`. That is expected.

A metadata smoke, synthetic fixture, or unit test is useful supporting evidence.
It may count only at the evidence level it actually demonstrates. It must not
be counted as field/client evidence for:

- real dedicated server boot
- real client menu entry
- real human-observed client/server login
- real 3-to-5 minute play-session smoke
- real GUI/client datapack reload observation
- real external corpus compatibility
- real native-vs-InterMed performance baseline
- real medium mixed-pack soak

Rows imported from JUnit must keep synthetic wording in `result.json` and their
supporting reports. Rows that explicitly require visual client or soak evidence
remain `not-run` until the required scenario itself runs and emits artifacts.

## Useful Environment Variables

- `INTERMED_TEST_DATE`: override the date directory.
- `INTERMED_TEST_RUN_ROOT`: override the suite output directory.
- `INTERMED_TEST_SUITE`: override the suite name.
- `INTERMED_ENV_ID`: record the environment row.
- `INTERMED_MACHINE_CLASS`: record `HW-A`, `HW-B`, or another explicit class.
- `JAVA_BIN` or `INTERMED_JAVA`: choose the Java executable.
- `GRADLE_BIN`: choose the Gradle wrapper path.
- `INTERMED_RUN_LOCAL_GATES=true`: run heavy local Gradle hard gates.
- `INTERMED_RUN_SMOKE_SUITE=true`: run Gradle-backed smoke accounting.
- `INTERMED_RUN_SECURITY_SUITE=true`: run `:app:strictSecurity`.
- `INTERMED_RUN_CLIENT_SERVER_SMOKE=true`: prepare the shared client/server smoke session bundle.
- `INTERMED_CLIENT_SERVER_OBSERVATIONS=/path/to/observation.json`: import manual observation results into `BOOT-005`, `REG-004`, `NET-001..005`, and `VFS-002..004`.
- `INTERMED_CLIENT_SERVER_NIGHTLY_OUT` / `INTERMED_CLIENT_SERVER_WEEKLY_OUT`: override the two output directories for the client/server multi-suite wrapper. If unset, `INTERMED_TEST_RUN_ROOT` is treated as the shared run root for those two suite directories.
- `INTERMED_RUN_EXTERNAL_CORPUS=true`: enable external corpus execution.
- `INTERMED_RUN_PERF_BASELINE=true`: enable performance baseline execution.
- `INTERMED_RUN_SOAK=true`: run `:app:runtimeSoak`.
- `INTERMED_RUN_MEDIUM_SOAK=true`: request the medium soak path.

By default, wrappers avoid heavy Gradle, Minecraft, corpus, performance, and
soak execution. They still emit `not-run` artifacts so the dashboard can show
what remains unproven.

## Dashboard

Generate a dashboard from all available suite summaries:

```bash
testing/promote_results.py
```

Generate from a specific run root:

```bash
testing/promote_results.py --run-root build/test-runs/20260422
```

Generate JSON plus a Markdown summary:

```bash
testing/promote_results.py \
  --run-root build/test-runs/20260422 \
  --output build/test-runs/20260422/beta-readiness-dashboard.json \
  --markdown-output build/test-runs/20260422/beta-readiness-dashboard.md
```

The dashboard intentionally treats missing frozen beta candidate results as
`not-run`.
