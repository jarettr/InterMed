# Alpha Security Posture - 2026-04-21

This note defines the open-alpha security posture for InterMed
`v8.0-alpha-snapshot`. It is intentionally narrow: strict mode is useful for
testers, but it is not a field-proven hostile-mod security claim.

## What Is In Scope

- `CapabilityDeniedException` is the fail-closed strict-mode exception.
- Denial messages include mod id, requested capability, scoped path/host/member,
  reason, and recommended next action.
- Denials and permissive warnings are written to `logs/intermed-security.log`.
- External profiles are loaded from `config/intermed-security-profiles.json`.
- Example profiles are provided in `examples/security-profiles/`.
- `:app:strictSecurity` includes synthetic hostile smoke coverage.

## Strict vs Permissive

- `security.strict.mode=true`: missing or out-of-scope capability checks throw
  `CapabilityDeniedException`.
- `security.strict.mode=false`: violations are logged for diagnosis and allowed
  for compatibility triage.
- A permissive harness pass is never described as secure. It only means the
  permissive compatibility lane booted under its documented settings.

## Synthetic Hostile Smoke

The strict-security lane covers these minimum hostile cases:

- forbidden file read
- forbidden file write
- forbidden socket/network host
- private reflection access
- process spawn
- native library load
- Unsafe attempt
- VarHandle attempt
- FFM attempt
- async attribution through `Thread`
- async attribution through `Executor`
- async attribution through `CompletableFuture`

The generated report is:

```text
app/build/reports/security/hostile-smoke.txt
```

## Non-Claims

- This is not a pentest.
- This does not prove sandbox escape resistance for arbitrary public mods.
- This does not prove hostile JNI/JNA behavior in native-heavy public mods.
- This does not make permissive compatibility harness results secure.
