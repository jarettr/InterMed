# Alpha Performance Snapshot - 2026-04-21

This note defines the performance evidence posture for InterMed
`v8.0.0-alpha.1`.

## Current Artifact

The lightweight snapshot is generated at:

```text
app/build/reports/performance/alpha-performance-snapshot.json
```

It records:

- clean Fabric native baseline
- clean Forge native baseline
- InterMed-attached clean Fabric baseline
- startup time
- heap and metaspace observations
- GC pause data from the lane GC log
- short dedicated-server tick smoke timing
- JFR dump path and byte size
- registry/remapper/event-bus microbench artifact presence

The native-loader baseline mirror is generated at:

```text
app/build/reports/performance/native-loader-baseline.json
```

## Interpretation

This is an initial alpha performance snapshot. It is not a final target report
and must not be cited as proof of the `10-15%` steady-state overhead goal.

The existing registry/remapper/event-bus reports are internal hot-path evidence.
They are useful for regression detection, but they are not real modpack overhead
or native-loader parity evidence.

## Native Baseline Status

Capture the lanes with the lightweight harness command:

```text
./test-harness/run.sh performance-baseline --heap=768 --timeout=180
```

The command runs three short dedicated-server lanes:

- `native-fabric-clean`
- `native-forge-clean`
- `intermed-attached-fabric-clean`

Each valid lane records:

- startup time
- heap usage
- metaspace usage
- GC pause/collection data
- short tick smoke timing
- JFR dump

Do not publish an overhead percentage until both native lanes and the matching
InterMed-attached lane are captured under comparable settings.
