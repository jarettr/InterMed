# Contributing To InterMed

Thanks for helping test InterMed. This repository is currently in the
`v8.0.0-alpha.2` open-alpha phase for Minecraft `1.20.1` only. The project is
not yet production-ready, and issue reports are most useful when they include
reproducible diagnostics rather than broad compatibility claims.

## Current Alpha Scope

- Minecraft baseline: `1.20.1`
- Loader families under alpha scope: Fabric, Forge, and NeoForge
- Java baseline: Java 21
- Release posture: open alpha only
- Not claimed: production stability, general `1.20+` compatibility, `95%`
  public-mod compatibility, field-proven hostile-mod security, or real-modpack
  `10-15%` overhead

## Before Opening An Issue

1. Check [docs/known-limitations.md](docs/known-limitations.md).
2. Check [docs/alpha-triage.md](docs/alpha-triage.md).
3. Use the closest issue template.
4. Attach a diagnostics bundle when possible.

If `InterMedLauncher launch` fails, it should write a diagnostics bundle
automatically unless disabled. You can also generate one manually:

```bash
java -cp InterMedCore-8.0.0-alpha.2.jar org.intermed.launcher.InterMedLauncher diagnostics-bundle \
  --game-dir /path/to/game \
  --mods-dir /path/to/game/intermed_mods
```

## Useful Issue Labels

Maintainers use these labels to keep alpha triage calm and searchable:

- `compatibility`: Fabric / Forge / NeoForge API or mod behavior gaps
- `crash`: launch, bootstrap, runtime crash, or hard failure
- `security`: capability, sandbox, or vulnerability-reporting work
- `performance`: TPS, startup, memory, JFR, or hot-path regressions
- `docs`: README, guides, release notes, or confusing user instructions
- `alpha-blocker`: issues that should block the next alpha release

## Pull Requests

- Keep PRs focused. One compatibility surface, bug, doc fix, or report change is
  easier to review than a mixed bundle.
- Add or update tests for runtime behavior changes.
- Do not broaden public claims in docs unless there is matching evidence.
- Keep generated artifacts out of Git. CI/release artifacts are the right place
  for raw reports, JFR files, logs, and release payloads.
- Run the most relevant local gate before opening a PR:

```bash
./gradlew :app:test :app:coverageGate :app:strictSecurity :app:verifyRuntime --rerun-tasks -Dintermed.allowRemoteForgeRepo=true --console=plain
./gradlew :test-harness:test --rerun-tasks --console=plain
```

## Security Reports

Please do not open public issues for suspected vulnerabilities. Use GitHub
private vulnerability reporting when available, or follow the contact guidance
in [.github/SECURITY.md](.github/SECURITY.md).

## Development Notes

- Use Java 21.
- Prefer `./gradlew` from the repository root.
- Keep the frozen alpha target at Minecraft `1.20.1` unless a separate
  validation matrix is added.
- If a change affects launch instructions, update
  [docs/user-guide.md](docs/user-guide.md), [README.md](README.md), and release
  notes together.
