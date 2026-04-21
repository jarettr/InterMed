# TLauncher + Java 21 for InterMed

This guide is the simplest user-facing path for running InterMed with TLauncher on this machine.

## Why this matters

InterMed targets Java 21+. Some launchers, including TLauncher, may default to an older bundled Java runtime. If Minecraft is launched on Java 8/17 or another mismatched runtime, InterMed may fail before the game fully starts.

For this machine, use:

- Java executable: `/home/mak/.local/jdks/temurin21-full/jdk-21.0.10+7/bin/java`

## TLauncher launch settings

In TLauncher, open the version/profile settings and make sure the Java path points to:

```text
/home/mak/.local/jdks/temurin21-full/jdk-21.0.10+7/bin/java
```

## JVM arguments for InterMed

Add this to the JVM arguments for the game profile:

```text
-javaagent:/home/mak/Projects/InterMed/app/build/libs/InterMedCore-8.0.0-alpha.1.jar
```

If you want the explicit mods directory too, add:

```text
-javaagent:/home/mak/Projects/InterMed/app/build/libs/InterMedCore-8.0.0-alpha.1.jar -Dintermed.modsDir=/path/to/.minecraft/intermed_mods
```

## Recommended Minecraft baseline

Current project baseline in this repository:

- Minecraft `1.20.1`
- Java `21+`

## First practical checks

Before launching from TLauncher:

1. Build the core JAR:

```bash
cd /home/mak/Projects/InterMed
./gradlew :app:coreJar
```

2. Verify the artifact exists:

```bash
ls -lah /home/mak/Projects/InterMed/app/build/libs/InterMedCore-8.0.0-alpha.1.jar
```

3. In TLauncher, force Java 21 and add the `-javaagent` argument shown above.

## Suggested starter mod sets

### Set 1: minimal smoke

- `fabric-api`
- `jei`

### Set 2: dependency stress

- `fabric-api`
- `architectury-api`
- `cloth-config`
- `jei`

### Set 3: mixin and bridge pressure

- `fabric-api`
- `architectury-api`
- `cloth-config`
- `jei`
- `jade`
- `modernfix`

These sets are only starting points. For large automatic sweeps, use `test-harness` instead of manual launcher setup.
