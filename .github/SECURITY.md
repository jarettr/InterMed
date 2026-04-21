# Security Policy

InterMed `v8.0.0-alpha.1` is not yet a production security boundary. The
strict security lane is actively hardened, but field-proven hostile-mod security
is not claimed.

## What To Report Privately

Use a private security report for:

- a reliable sandbox escape
- a capability bypass in strict mode
- a way for a mod to read/write files outside its granted paths
- a way for a mod to open network connections outside its grants
- a native/JNI/JNA, Unsafe, VarHandle, or FFM bypass
- sensitive exploit details that should not be public before triage

Do not attach private exploit code or secrets to a public issue.

## What Can Be Public

Use the public `Security hardening` issue template for:

- unclear denial messages
- missing diagnostics in strict mode
- policy configuration confusion
- non-sensitive false positive/false negative reports
- requests for narrower built-in capability profiles

## Required Evidence

When safe to share, include:

- InterMed build or commit
- Java version and OS
- strict/permissive mode
- relevant capability profile or sanitized config
- diagnostics bundle or sanitized log
- smallest reproducible mod/test case

## Current Claim

The current claim is `Synthetic-tested` strict-security hardening for the frozen
`1.20.1` alpha path. Production security and hostile public-mod guarantees remain
deferred until field validation exists.
