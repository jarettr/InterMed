package org.intermed.launcher;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.intermed.core.bridge.ForgeNetworkBridge;
import org.intermed.core.bridge.InterMedNetworkBridge;
import org.intermed.core.bridge.NeoForgeNetworkBridge;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.lifecycle.ModDiscovery;
import org.intermed.core.metadata.ModMetadataParser;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.report.ApiGapMatrixGenerator;
import org.intermed.core.report.CompatibilityCorpusGenerator;
import org.intermed.core.report.CompatibilityReportGenerator;
import org.intermed.core.report.CompatibilitySweepMatrixGenerator;
import org.intermed.core.report.LaunchDiagnosticsBundle;
import org.intermed.core.report.LaunchReadinessReportGenerator;
import org.intermed.core.report.ModSbomGenerator;
import org.intermed.core.sandbox.GraalVMSandbox;
import org.intermed.core.sandbox.PolyglotSandboxManager;
import org.intermed.core.sandbox.WasmSandbox;
import org.intermed.core.security.SecurityPolicy;

public class InterMedLauncher {
    private static final DateTimeFormatter DIAGNOSTICS_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);
    private static final List<String> REQUIRED_JVM_OPENS = List.of(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED"
    );

    public static void main(String[] args) throws Exception {
        int exitCode = execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int execute(String[] args) throws Exception {
        LaunchRequest request = parse(args);
        return switch (request.command()) {
            case "launch" -> executeLaunch(request);
            case "doctor" -> executeDoctor(request);
            case "compat-smoke" -> executeCompatSmoke(request);
            case "compat-report" -> executeCompatReport(request);
            case "compat-corpus" -> executeCompatCorpus(request);
            case "compat-sweep-matrix" -> executeCompatSweepMatrix(request);
            case "launch-kit" -> executeLaunchKit(request);
            case "alpha-release-reports" -> executeAlphaReleaseReports(request);
            case "sbom" -> executeSbom(request);
            case "api-gap-matrix" -> executeApiGapMatrix(request);
            case "launch-readiness-report" -> executeLaunchReadinessReport(request);
            case "diagnostics-bundle" -> executeDiagnosticsBundle(request);
            case "help", "--help", "-h" -> {
                printUsage();
                yield 0;
            }
            default -> {
                System.err.println("[Launcher] Unknown command: " + request.command());
                printUsage();
                yield 2;
            }
        };
    }

    static LaunchRequest parse(String[] args) {
        String command = args.length == 0 ? "doctor" : args[0];
        Map<String, List<String>> options = new LinkedHashMap<>();
        List<String> passthrough = new ArrayList<>();
        boolean passthroughMode = false;

        for (int i = 1; i < args.length; i++) {
            String argument = args[i];
            if ("--".equals(argument)) {
                passthroughMode = true;
                continue;
            }
            if (passthroughMode) {
                passthrough.add(argument);
                continue;
            }
            if (argument.startsWith("--")) {
                String key = argument.substring(2);
                String value = "true";
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i];
                }
                options.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
            } else {
                passthrough.add(argument);
            }
        }

        return new LaunchRequest(command, options, passthrough);
    }

    static List<String> buildLaunchCommand(LaunchRequest request) throws Exception {
        RuntimeConfig.reload();

        LaunchPaths paths = resolveLaunchPaths(request);
        Path agentPath = Path.of(request.option("agent").orElse(resolveDefaultAgentPath().toString()))
            .toAbsolutePath()
            .normalize();
        List<String> command = new ArrayList<>();
        command.add(request.option("java").orElse(defaultJavaCommand()));
        command.addAll(buildJvmAgentArgs(agentPath, paths, request.option("mappings")
            .map(path -> Path.of(path).toAbsolutePath().normalize())));

        Optional<String> jar = request.option("jar");
        if (jar.isPresent()) {
            command.add("-jar");
            command.add(jar.get());
        } else {
            String mainClass = request.option("main-class")
                .orElseThrow(() -> new IllegalArgumentException("launch requires --jar or --main-class"));
            request.option("classpath").ifPresent(classpath -> {
                command.add("-cp");
                command.add(classpath);
            });
            command.add(mainClass);
        }

        command.addAll(request.passthrough());
        return command;
    }

    private static int executeLaunch(LaunchRequest request) throws Exception {
        List<String> command = buildLaunchCommand(request);
        if (request.hasFlag("dry-run")) {
            System.out.println(command.stream().map(InterMedLauncher::quoteForDisplay).collect(Collectors.joining(" ")));
            return 0;
        }
        Process process = new ProcessBuilder(command).inheritIO().start();
        int exitCode = process.waitFor();
        if (exitCode != 0 && !request.hasFlag("no-diagnostics-bundle")) {
            writeLaunchFailureDiagnostics(request, exitCode);
        }
        return exitCode;
    }

    private static int executeDoctor(LaunchRequest request) throws Exception {
        DoctorReport report = doctor(request);
        report.lines().forEach(System.out::println);
        return report.hasErrors() ? 1 : 0;
    }

    private static int executeCompatSmoke(LaunchRequest request) throws Exception {
        int doctorExit = executeDoctor(request);

        RuntimeConfig.reload();
        List<File> jars = discoverJars(request);
        int failures = 0;
        System.out.println("[CompatSmoke] Discovered JARs: " + jars.size());
        for (File jar : jars) {
            try {
                Optional<NormalizedModMetadata> metadata = ModMetadataParser.parse(jar);
                if (metadata.isEmpty()) {
                    failures++;
                    System.out.println("[CompatSmoke] FAIL " + jar.getName() + " -> unsupported/no manifest");
                    continue;
                }
                NormalizedModMetadata mod = metadata.get();
                System.out.println("[CompatSmoke] OK   " + mod.id() + " [" + mod.platform() + " " + mod.version() + "]");
            } catch (Exception e) {
                failures++;
                System.out.println("[CompatSmoke] FAIL " + jar.getName() + " -> " + e.getMessage());
            }
        }
        request.option("report").ifPresent(path -> {
            try {
                CompatibilityReportGenerator.writeReport(Path.of(path), jars);
            } catch (Exception e) {
                throw new IllegalStateException("Failed to write compatibility report", e);
            }
        });
        return doctorExit != 0 || failures > 0 ? 1 : 0;
    }

    private static int executeLaunchKit(LaunchRequest request) throws Exception {
        RuntimeConfig.reload();
        LaunchPaths paths = resolveLaunchPaths(request);
        Path outputDir = request.option("output-dir")
            .map(path -> Path.of(path).toAbsolutePath().normalize())
            .orElse(paths.gameDir().resolve(".intermed/launch-kit").toAbsolutePath().normalize());
        Path runtimeDir = outputDir.resolve("runtime");
        Files.createDirectories(runtimeDir);

        Path genericSource = request.option("agent")
            .map(path -> Path.of(path).toAbsolutePath().normalize())
            .orElseGet(() -> {
                try {
                    return resolveDefaultGenericAgentPath();
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to resolve default generic InterMed agent jar", e);
                }
            });
        if (!Files.isRegularFile(genericSource)) {
            throw new IllegalArgumentException("Generic InterMed agent jar not found: " + genericSource);
        }

        Optional<Path> fabricSource = request.option("fabric-agent")
            .map(path -> Path.of(path).toAbsolutePath().normalize())
            .or(() -> {
                try {
                    return resolveDefaultFabricAgentPath();
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to resolve default Fabric InterMed agent jar", e);
                }
            });

        Path stagedMappings = resolveMappingsPath(request)
            .map(path -> stageArtifact(path, runtimeDir.resolve("mappings.tiny")))
            .orElse(null);

        List<LaunchKitVariant> variants = new ArrayList<>();
        variants.add(writeLaunchKitVariant("generic", genericSource, outputDir, runtimeDir, paths, stagedMappings));
        fabricSource
            .filter(Files::isRegularFile)
            .filter(path -> !path.equals(genericSource))
            .ifPresent(path -> variants.add(writeLaunchKitVariant("fabric", path, outputDir, runtimeDir, paths, stagedMappings)));

        writeLaunchKitReadme(outputDir, paths, variants, stagedMappings != null);
        writeLaunchKitManifest(outputDir, paths, variants, stagedMappings);

        System.out.println("[LaunchKit] Wrote launch kit to " + outputDir);
        variants.forEach(variant -> {
            System.out.println("[LaunchKit] " + variant.profile() + " JVM args: " + variant.launcherSnippet());
            System.out.println("[LaunchKit] " + variant.profile() + " shell wrapper: " + variant.shellScript());
            System.out.println("[LaunchKit] " + variant.profile() + " Windows wrapper: " + variant.cmdScript());
        });
        return 0;
    }

    private static int executeCompatReport(LaunchRequest request) throws Exception {
        RuntimeConfig.reload();
        Path output = resolveOutputPath(request, "output", "intermed-compat-report.json");
        CompatibilityReportGenerator.writeReport(output, discoverCandidateArchives(request));
        System.out.println("[CompatReport] Wrote " + output);
        return 0;
    }

    private static int executeCompatCorpus(LaunchRequest request) throws Exception {
        RuntimeConfig.reload();
        Path output = resolveOutputPath(request, "output", "intermed-compatibility-corpus.json");
        CompatibilityCorpusGenerator.writeReport(output, discoverCandidateArchives(request));
        System.out.println("[CompatCorpus] Wrote " + output);
        return 0;
    }

    private static int executeCompatSweepMatrix(LaunchRequest request) throws Exception {
        RuntimeConfig.reload();
        Path output = resolveOutputPath(request, "output", "intermed-compatibility-sweep-matrix.json");
        Path results = request.option("results")
            .map(path -> Path.of(path).toAbsolutePath().normalize())
            .orElse(null);
        if (request.option("corpus").isPresent()) {
            Path corpus = Path.of(request.option("corpus").get()).toAbsolutePath().normalize();
            CompatibilitySweepMatrixGenerator.writeReport(output, corpus, results);
        } else {
            CompatibilitySweepMatrixGenerator.writeReport(
                output,
                CompatibilityCorpusGenerator.generate(discoverCandidateArchives(request)),
                results
            );
        }
        System.out.println("[CompatSweepMatrix] Wrote " + output);
        return 0;
    }

    private static int executeAlphaReleaseReports(LaunchRequest request) throws Exception {
        RuntimeConfig.reload();
        LaunchPaths paths = resolveLaunchPaths(request);
        Path projectRoot = request.option("project-root")
            .map(path -> Path.of(path).toAbsolutePath().normalize())
            .orElse(Path.of(".").toAbsolutePath().normalize());
        Path outputDir = request.option("output-dir")
            .map(path -> Path.of(path).toAbsolutePath().normalize())
            .orElse(paths.gameDir().resolve("alpha-release-reports").toAbsolutePath().normalize());
        Files.createDirectories(outputDir);

        List<File> candidateArchives = ModDiscovery.discoverCandidateArchives(paths.modsDir().toFile());
        Path harnessResults = resolveHarnessResults(request, paths);

        Path compatibilityCorpus = outputDir.resolve("intermed-compatibility-corpus.json");
        Path compatibilitySweepMatrix = outputDir.resolve("intermed-compatibility-sweep-matrix.json");
        Path sbom = outputDir.resolve("intermed-sbom.cdx.json");
        Path apiGapMatrix = outputDir.resolve("intermed-api-gap-matrix.json");
        Path launchReadiness = outputDir.resolve("intermed-launch-readiness-report.json");

        CompatibilityCorpusGenerator.writeReport(compatibilityCorpus, candidateArchives);
        CompatibilitySweepMatrixGenerator.writeReport(
            compatibilitySweepMatrix,
            compatibilityCorpus,
            harnessResults
        );
        ModSbomGenerator.writeSbom(sbom, candidateArchives);
        ApiGapMatrixGenerator.writeReport(apiGapMatrix);
        LaunchReadinessReportGenerator.writeReport(
            launchReadiness,
            projectRoot,
            paths.gameDir(),
            paths.modsDir(),
            harnessResults
        );

        Path performanceSnapshot = resolvePerformanceSnapshot(request, projectRoot);
        if (performanceSnapshot != null) {
            Path copiedSnapshot = outputDir.resolve("alpha-performance-snapshot.json");
            Files.createDirectories(copiedSnapshot.getParent());
            Files.copy(performanceSnapshot, copiedSnapshot, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[AlphaReleaseReports] Copied performance snapshot to " + copiedSnapshot);
        }

        System.out.println("[AlphaReleaseReports] Wrote " + launchReadiness);
        System.out.println("[AlphaReleaseReports] Wrote " + apiGapMatrix);
        System.out.println("[AlphaReleaseReports] Wrote " + compatibilityCorpus);
        System.out.println("[AlphaReleaseReports] Wrote " + compatibilitySweepMatrix);
        System.out.println("[AlphaReleaseReports] Wrote " + sbom);
        return 0;
    }

    private static int executeSbom(LaunchRequest request) throws Exception {
        RuntimeConfig.reload();
        Path output = resolveOutputPath(request, "output", "intermed-sbom.cdx.json");
        ModSbomGenerator.writeSbom(output, discoverCandidateArchives(request));
        System.out.println("[SBOM] Wrote " + output);
        return 0;
    }

    private static int executeApiGapMatrix(LaunchRequest request) throws Exception {
        RuntimeConfig.reload();
        Path output = resolveOutputPath(request, "output", "intermed-api-gap-matrix.json");
        ApiGapMatrixGenerator.writeReport(output);
        System.out.println("[ApiGapMatrix] Wrote " + output);
        return 0;
    }

    private static int executeLaunchReadinessReport(LaunchRequest request) throws Exception {
        RuntimeConfig.reload();
        LaunchPaths paths = resolveLaunchPaths(request);
        Path output = resolveOutputPath(request, "output", "intermed-launch-readiness-report.json");
        Path projectRoot = request.option("project-root")
            .map(path -> Path.of(path).toAbsolutePath().normalize())
            .orElse(Path.of(".").toAbsolutePath().normalize());
        LaunchReadinessReportGenerator.writeReport(
            output,
            projectRoot,
            paths.gameDir(),
            paths.modsDir(),
            resolveHarnessResults(request, paths)
        );
        System.out.println("[LaunchReadiness] Wrote " + output);
        return 0;
    }

    private static int executeDiagnosticsBundle(LaunchRequest request) throws Exception {
        RuntimeConfig.reload();
        LaunchPaths paths = resolveLaunchPaths(request);
        Path output = request.option("output")
            .map(path -> Path.of(path).toAbsolutePath().normalize())
            .orElse(paths.gameDir().resolve("intermed-diagnostics-bundle.zip").toAbsolutePath().normalize());
        LaunchDiagnosticsBundle.BundleResult result = LaunchDiagnosticsBundle.writeBundle(
            output,
            ModDiscovery.discoverCandidateArchives(paths.modsDir().toFile()),
            paths.gameDir(),
            paths.modsDir(),
            paths.configDir(),
            resolveHarnessResults(request, paths)
        );
        System.out.println("[DiagnosticsBundle] Wrote " + result.archive()
            + " (" + result.entries().size() + " entries)");
        return 0;
    }

    private static void writeLaunchFailureDiagnostics(LaunchRequest request, int exitCode) {
        try {
            RuntimeConfig.reload();
            LaunchPaths paths = resolveLaunchPaths(request);
            Path output = request.option("diagnostics-output")
                .map(path -> Path.of(path).toAbsolutePath().normalize())
                .orElseGet(() -> defaultFailureBundlePath(paths.gameDir(), exitCode));
            output = uniquePath(output);
            LaunchDiagnosticsBundle.BundleResult result = LaunchDiagnosticsBundle.writeBundle(
                output,
                ModDiscovery.discoverCandidateArchives(paths.modsDir().toFile()),
                paths.gameDir(),
                paths.modsDir(),
                paths.configDir(),
                resolveHarnessResults(request, paths)
            );
            System.err.println("[DiagnosticsBundle] Launch exited with code " + exitCode
                + "; wrote " + result.archive()
                + " (" + result.entries().size() + " entries)");
        } catch (Exception e) {
            System.err.println("[DiagnosticsBundle] Failed to write launch-failure bundle: " + e.getMessage());
        }
    }

    static DoctorReport doctor(LaunchRequest request) throws Exception {
        RuntimeConfig.reload();

        List<String> lines = new ArrayList<>();
        boolean hasErrors = false;

        Path javaCommand = Path.of(request.option("java").orElse(defaultJavaCommand()));
        int javaVersion = Runtime.version().feature();
        lines.add("[Doctor] Java runtime: " + javaVersion + " (" + javaCommand + ")");
        if (javaVersion < 21) {
            lines.add("[Doctor] FAIL Java 21+ is required.");
            hasErrors = true;
        } else {
            lines.add("[Doctor] OK   Java level is compatible with runtime target.");
        }

        Path agentPath = Path.of(request.option("agent").orElse(resolveDefaultAgentPath().toString()));
        if (Files.exists(agentPath)) {
            lines.add("[Doctor] OK   Agent/core jar: " + agentPath);
            if (Files.isRegularFile(agentPath) && agentPath.getFileName().toString().endsWith(".jar")) {
                Path bootstrapJar = resolveBootstrapSupportPath(agentPath);
                if (Files.exists(bootstrapJar)) {
                    lines.add("[Doctor] OK   Bootstrap support jar: " + bootstrapJar);
                } else {
                    lines.add("[Doctor] FAIL Bootstrap support jar missing: " + bootstrapJar);
                    hasErrors = true;
                }
            } else {
                lines.add("[Doctor] INFO Bootstrap support jar check skipped for non-jar agent path.");
            }
        } else {
            lines.add("[Doctor] FAIL Agent/core jar missing: " + agentPath);
            hasErrors = true;
        }

        Path gameDir = Path.of(request.option("game-dir").orElse(RuntimeConfig.get().getGameDir().toString()));
        Path defaultModsDir = request.option("game-dir").isPresent()
            ? gameDir.resolve("intermed_mods")
            : RuntimeConfig.get().getModsDir();
        Path modsDir = Path.of(request.option("mods-dir").orElse(defaultModsDir.toString()));
        Path configDir = request.option("game-dir").isPresent()
            ? gameDir.resolve("config").toAbsolutePath().normalize()
            : RuntimeConfig.get().getConfigDir();
        lines.add("[Doctor] Game dir: " + gameDir);
        lines.add("[Doctor] Config dir: " + configDir);

        if (Files.exists(modsDir) || Files.isWritable(modsDir.getParent() == null ? gameDir : modsDir.getParent())) {
            lines.add("[Doctor] OK   Mods dir ready: " + modsDir);
        } else {
            lines.add("[Doctor] FAIL Mods dir is not available: " + modsDir);
            hasErrors = true;
        }

        resolveMappingsPath(request).ifPresentOrElse(
            path -> lines.add("[Doctor] OK   Mappings file: " + path),
            () -> lines.add("[Doctor] WARN Mappings file not found; remapping will degrade.")
        );

        GraalVMSandbox.HostStatus espressoStatus = GraalVMSandbox.probeAvailability();
        WasmSandbox.HostStatus wasmStatus = WasmSandbox.probeAvailability();
        lines.add(espressoStatus.isReady()
            ? "[Doctor] OK   Espresso sandbox ready: " + espressoStatus.state()
            : "[Doctor] WARN Espresso sandbox not ready: " + espressoStatus.state());
        lines.add(wasmStatus.isReady()
            ? "[Doctor] OK   Chicory sandbox ready: " + wasmStatus.state()
            : "[Doctor] WARN Chicory sandbox not ready: " + wasmStatus.state());
        lines.add(RuntimeConfig.get().isSecurityStrictMode()
            ? "[Doctor] OK   Capability security strict mode enabled."
            : "[Doctor] WARN Capability security strict mode disabled.");
        lines.add(RuntimeConfig.get().isNativeSandboxFallbackEnabled()
            ? "[Doctor] WARN Native sandbox fallback enabled."
            : "[Doctor] OK   Native sandbox fallback disabled by default.");
        Path externalProfiles = configDir.resolve(SecurityPolicy.EXTERNAL_PROFILES_FILE).toAbsolutePath().normalize();
        lines.add(Files.exists(externalProfiles)
            ? "[Doctor] OK   Security profiles file: " + externalProfiles
            : "[Doctor] WARN Security profiles file not found: " + externalProfiles);
        PolyglotSandboxManager.SandboxRuntimeDiagnostics sandboxDiagnostics = PolyglotSandboxManager.diagnostics();
        lines.add("[Doctor] Sandbox host contract functions: " + PolyglotSandboxManager.hostExportCount());
        lines.add("[Doctor] Sandbox host contract digest: " + sandboxDiagnostics.hostContractDigest());
        lines.add("[Doctor] Sandbox runtime caches: plans=" + sandboxDiagnostics.planCacheEntries()
            + ", executions=" + sandboxDiagnostics.executionCacheEntries()
            + ", wasmModules=" + sandboxDiagnostics.wasmModuleCacheEntries()
            + " (hits=" + sandboxDiagnostics.wasmModuleCacheHits()
            + ", misses=" + sandboxDiagnostics.wasmModuleCacheMisses() + ")");
        InterMedNetworkBridge.NetworkBridgeDiagnostics networkDiagnostics = InterMedNetworkBridge.diagnostics();
        lines.add("[Doctor] Network bridge protocol: v" + networkDiagnostics.protocolVersion()
            + " via " + networkDiagnostics.transportChannel());
        lines.add("[Doctor] Network bridge channels: " + networkDiagnostics.registeredChannels()
            + " registered, encoded=" + networkDiagnostics.encodedEnvelopes()
            + ", dispatched=" + networkDiagnostics.dispatchedEnvelopes()
            + ", dropped=" + networkDiagnostics.droppedEnvelopes());
        ForgeNetworkBridge.ForgeNetworkDiagnostics forgeDiagnostics = ForgeNetworkBridge.diagnostics();
        lines.add("[Doctor] Forge SimpleImpl bridge: channels=" + forgeDiagnostics.registeredChannels()
            + ", messages=" + forgeDiagnostics.registeredMessages()
            + ", dispatched=" + forgeDiagnostics.dispatchedMessages());
        NeoForgeNetworkBridge.NeoForgeNetworkDiagnostics neoForgeDiagnostics = NeoForgeNetworkBridge.diagnostics();
        lines.add("[Doctor] NeoForge payload bridge: payloads=" + neoForgeDiagnostics.registeredPayloads()
            + ", listeners=" + (neoForgeDiagnostics.listenersRegistered() ? "attached" : "idle")
            + ", observed=" + neoForgeDiagnostics.observedEventClass());

        return new DoctorReport(lines, hasErrors);
    }

    private static Path resolveOutputPath(LaunchRequest request, String key, String defaultFileName) {
        if (request.option(key).isPresent()) {
            return Path.of(request.option(key).get()).toAbsolutePath().normalize();
        }
        return RuntimeConfig.get().getGameDir().resolve(defaultFileName).toAbsolutePath().normalize();
    }

    private static LaunchPaths resolveLaunchPaths(LaunchRequest request) {
        Path gameDir = Path.of(request.option("game-dir").orElse(RuntimeConfig.get().getGameDir().toString()))
            .toAbsolutePath()
            .normalize();
        Path defaultModsDir = request.option("game-dir").isPresent()
            ? gameDir.resolve("intermed_mods")
            : RuntimeConfig.get().getModsDir();
        Path modsDir = Path.of(request.option("mods-dir").orElse(defaultModsDir.toString()))
            .toAbsolutePath()
            .normalize();
        Path configDir = request.option("game-dir").isPresent()
            ? gameDir.resolve("config").toAbsolutePath().normalize()
            : RuntimeConfig.get().getConfigDir();
        return new LaunchPaths(gameDir, modsDir, configDir);
    }

    private static Path defaultFailureBundlePath(Path gameDir, int exitCode) {
        String timestamp = DIAGNOSTICS_TIMESTAMP.format(Instant.now());
        return gameDir.resolve(".intermed")
            .resolve("diagnostics")
            .resolve("intermed-launch-failure-exit" + exitCode + "-" + timestamp + ".zip")
            .toAbsolutePath()
            .normalize();
    }

    private static Path uniquePath(Path path) {
        if (!Files.exists(path)) {
            return path;
        }
        String fileName = path.getFileName() == null ? "intermed-diagnostics-bundle.zip" : path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        Path parent = path.getParent() == null ? Path.of(".") : path.getParent();
        int counter = 2;
        Path candidate;
        do {
            candidate = parent.resolve(stem + "-" + counter + extension).toAbsolutePath().normalize();
            counter++;
        } while (Files.exists(candidate));
        return candidate;
    }

    private static Path resolveHarnessResults(LaunchRequest request, LaunchPaths paths) {
        if (request.option("harness-results").isPresent()) {
            return Path.of(request.option("harness-results").get()).toAbsolutePath().normalize();
        }
        List<Path> candidates = List.of(
            paths.gameDir().resolve(".intermed/harness/results.json"),
            paths.gameDir().resolve("harness-output/report/results.json")
        );
        for (Path candidate : candidates) {
            Path normalized = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(normalized)) {
                return normalized;
            }
        }
        return null;
    }

    private static List<File> discoverJars(LaunchRequest request) {
        return ModDiscovery.discoverJars(resolveLaunchPaths(request).modsDir().toFile());
    }

    private static List<File> discoverCandidateArchives(LaunchRequest request) {
        return ModDiscovery.discoverCandidateArchives(resolveLaunchPaths(request).modsDir().toFile());
    }

    private static List<String> buildJvmAgentArgs(Path agentPath,
                                                  LaunchPaths paths,
                                                  Optional<Path> mappingsPath) {
        List<String> args = new ArrayList<>();
        args.add("-javaagent:" + agentPath.toAbsolutePath().normalize());
        args.addAll(REQUIRED_JVM_OPENS);
        args.add("-Druntime.game.dir=" + paths.gameDir());
        args.add("-Druntime.mods.dir=" + paths.modsDir());
        args.add("-Dintermed.modsDir=" + paths.modsDir());
        mappingsPath
            .map(path -> path.toAbsolutePath().normalize())
            .ifPresent(path -> args.add("-Dintermed.mappings.tiny=" + path));
        return args;
    }

    private static Optional<Path> resolveMappingsPath(LaunchRequest request) throws Exception {
        if (request.option("mappings").isPresent()) {
            Path explicit = Path.of(request.option("mappings").get()).toAbsolutePath().normalize();
            return Files.exists(explicit) ? Optional.of(explicit) : Optional.empty();
        }
        Path devPath = Path.of("mappings/mappings.tiny").toAbsolutePath().normalize();
        if (Files.exists(devPath)) {
            return Optional.of(devPath);
        }
        Path jarDir = resolveSelfPath().getParent();
        if (jarDir != null) {
            Path bundled = jarDir.resolve("mappings/mappings.tiny").toAbsolutePath().normalize();
            if (Files.exists(bundled)) {
                return Optional.of(bundled);
            }
        }
        return Optional.empty();
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, InterMedLauncher.class.getClassLoader());
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String defaultJavaCommand() {
        String executable = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable).toString();
    }

    private static Path resolveDefaultAgentPath() throws Exception {
        Path self = resolveSelfPath();
        if (Files.isRegularFile(self)) {
            return self;
        }
        Path buildDir = Path.of("app/build/libs").toAbsolutePath().normalize();
        if (Files.isDirectory(buildDir)) {
            try (var candidates = Files.list(buildDir)) {
                Optional<Path> builtJar = candidates
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.startsWith("InterMedCore")
                            && fileName.endsWith(".jar")
                            && !fileName.endsWith("-bootstrap.jar")
                            && !fileName.endsWith("-fabric.jar")
                            && !fileName.endsWith("-thin.jar")
                            && !fileName.endsWith("-sources.jar");
                    })
                    .sorted()
                    .reduce((left, right) -> right);
                if (builtJar.isPresent()) {
                    return builtJar.get().toAbsolutePath().normalize();
                }
            }
        }
        return self;
    }

    private static Path resolveDefaultGenericAgentPath() throws Exception {
        Path self = resolveSelfPath();
        if (Files.isRegularFile(self)) {
            String fileName = self.getFileName() == null ? "" : self.getFileName().toString();
            if (!fileName.endsWith("-fabric.jar")
                && !fileName.endsWith("-bootstrap.jar")
                && fileName.endsWith(".jar")) {
                return self;
            }
            if (fileName.endsWith("-fabric.jar")) {
                String genericName = fileName.substring(0, fileName.length() - "-fabric.jar".length()) + ".jar";
                Path sibling = self.resolveSibling(genericName).toAbsolutePath().normalize();
                if (Files.isRegularFile(sibling)) {
                    return sibling;
                }
            }
        }
        Path buildDir = Path.of("app/build/libs").toAbsolutePath().normalize();
        if (Files.isDirectory(buildDir)) {
            try (var candidates = Files.list(buildDir)) {
                Optional<Path> builtJar = candidates
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.startsWith("InterMedCore")
                            && fileName.endsWith(".jar")
                            && !fileName.endsWith("-bootstrap.jar")
                            && !fileName.endsWith("-fabric.jar")
                            && !fileName.endsWith("-thin.jar")
                            && !fileName.endsWith("-sources.jar");
                    })
                    .sorted()
                    .reduce((left, right) -> right);
                if (builtJar.isPresent()) {
                    return builtJar.get().toAbsolutePath().normalize();
                }
            }
        }
        return resolveDefaultAgentPath();
    }

    private static Optional<Path> resolveDefaultFabricAgentPath() throws Exception {
        Path self = resolveSelfPath();
        if (Files.isRegularFile(self)) {
            String fileName = self.getFileName() == null ? "" : self.getFileName().toString();
            if (fileName.endsWith("-fabric.jar")) {
                return Optional.of(self);
            }
            if (fileName.endsWith(".jar")) {
                String fabricName = fileName.substring(0, fileName.length() - ".jar".length()) + "-fabric.jar";
                Path sibling = self.resolveSibling(fabricName).toAbsolutePath().normalize();
                if (Files.isRegularFile(sibling)) {
                    return Optional.of(sibling);
                }
            }
        }
        Path buildDir = Path.of("app/build/libs").toAbsolutePath().normalize();
        if (Files.isDirectory(buildDir)) {
            try (var candidates = Files.list(buildDir)) {
                return candidates
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        String fileName = path.getFileName().toString();
                        return fileName.startsWith("InterMedCore")
                            && fileName.endsWith("-fabric.jar");
                    })
                    .sorted()
                    .reduce((left, right) -> right)
                    .map(path -> path.toAbsolutePath().normalize());
            }
        }
        return Optional.empty();
    }

    private static Path resolveSelfPath() throws Exception {
        return Path.of(InterMedLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI())
            .toAbsolutePath()
            .normalize();
    }

    private static Path resolveBootstrapSupportPath(Path agentPath) {
        String fileName = agentPath.getFileName() == null ? "InterMedCore.jar" : agentPath.getFileName().toString();
        String bootstrapName = fileName.endsWith(".jar")
            ? fileName.substring(0, fileName.length() - 4) + "-bootstrap.jar"
            : fileName + "-bootstrap.jar";
        Path sibling = agentPath.resolveSibling(bootstrapName).toAbsolutePath().normalize();
        if (Files.exists(sibling)) {
            return sibling;
        }
        if (fileName.endsWith("-fabric.jar")) {
            String genericBootstrap = fileName.substring(0, fileName.length() - "-fabric.jar".length())
                + "-bootstrap.jar";
            return agentPath.resolveSibling(genericBootstrap).toAbsolutePath().normalize();
        }
        return sibling;
    }

    private static String quoteForDisplay(String token) {
        if (token.indexOf(' ') < 0 && token.indexOf('"') < 0) {
            return token;
        }
        return "\"" + token.replace("\"", "\\\"") + "\"";
    }

    private static void printUsage() {
        System.out.println("InterMed launcher commands:");
        System.out.println("  doctor [--agent PATH] [--mods-dir PATH] [--game-dir PATH] [--mappings PATH]");
        System.out.println("  compat-smoke [--mods-dir PATH] [--game-dir PATH] [--mappings PATH] [--report PATH]");
        System.out.println("  compat-report [--mods-dir PATH] [--game-dir PATH] [--output PATH]");
        System.out.println("  compat-corpus [--mods-dir PATH] [--game-dir PATH] [--output PATH]");
        System.out.println("  compat-sweep-matrix [--corpus PATH] [--results PATH] [--mods-dir PATH] [--game-dir PATH] [--output PATH]");
        System.out.println("  launch-kit [--agent PATH] [--fabric-agent PATH] [--mods-dir PATH] [--game-dir PATH] [--mappings PATH] [--output-dir PATH]");
        System.out.println("  alpha-release-reports [--project-root PATH] [--mods-dir PATH] [--game-dir PATH] [--harness-results PATH] [--performance-snapshot PATH] [--output-dir PATH]");
        System.out.println("  sbom [--mods-dir PATH] [--game-dir PATH] [--output PATH]");
        System.out.println("  api-gap-matrix [--game-dir PATH] [--output PATH]");
        System.out.println("  launch-readiness-report [--project-root PATH] [--mods-dir PATH] [--game-dir PATH] [--harness-results PATH] [--output PATH]");
        System.out.println("  diagnostics-bundle [--mods-dir PATH] [--game-dir PATH] [--harness-results PATH] [--output PATH]");
        System.out.println("  launch (--jar PATH | --main-class CLASS [--classpath CP]) [--agent PATH] [--mods-dir PATH] [--game-dir PATH] [--mappings PATH] [--harness-results PATH] [--dry-run] [--diagnostics-output PATH] [--no-diagnostics-bundle] [-- <minecraft args...>]");
    }

    private static LaunchKitVariant writeLaunchKitVariant(String profile,
                                                          Path sourceAgent,
                                                          Path outputDir,
                                                          Path runtimeDir,
                                                          LaunchPaths paths,
                                                          Path stagedMappings) {
        Path stagedAgent = stageArtifact(sourceAgent, runtimeDir.resolve(sourceAgent.getFileName()));
        Path stagedBootstrap = stageArtifact(
            resolveBootstrapSupportPath(sourceAgent),
            runtimeDir.resolve(resolveBootstrapSupportPath(sourceAgent).getFileName())
        );

        List<String> jvmArgs = buildJvmAgentArgs(stagedAgent, paths, Optional.ofNullable(stagedMappings));
        Path argFile = outputDir.resolve("intermed-java-" + profile + ".args").toAbsolutePath().normalize();
        Path launcherSnippet = outputDir.resolve("launcher-jvm-args-" + profile + ".txt").toAbsolutePath().normalize();
        Path shellScript = outputDir.resolve("intermed-launch-" + profile + ".sh").toAbsolutePath().normalize();
        Path cmdScript = outputDir.resolve("intermed-launch-" + profile + ".cmd").toAbsolutePath().normalize();

        writeUtf8(argFile, renderArgFile(jvmArgs));
        writeUtf8(launcherSnippet, renderLauncherSnippet(jvmArgs));
        writeUtf8(shellScript, renderShellWrapper(argFile.getFileName().toString()));
        writeUtf8(cmdScript, renderWindowsWrapper(argFile.getFileName().toString()));
        makeExecutableIfSupported(shellScript);

        return new LaunchKitVariant(
            profile,
            stagedAgent,
            stagedBootstrap,
            argFile,
            launcherSnippet,
            shellScript,
            cmdScript,
            jvmArgs
        );
    }

    private static Path stageArtifact(Path source, Path target) {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedTarget = target.toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalizedTarget.getParent());
            if (normalizedSource.equals(normalizedTarget)) {
                return normalizedTarget;
            }
            Files.copy(normalizedSource, normalizedTarget, StandardCopyOption.REPLACE_EXISTING);
            return normalizedTarget;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to stage launch-kit artifact " + normalizedSource
                + " -> " + normalizedTarget, e);
        }
    }

    private static String renderArgFile(List<String> tokens) {
        return tokens.stream()
            .map(InterMedLauncher::quoteForDisplay)
            .collect(Collectors.joining(System.lineSeparator(), "", System.lineSeparator()));
    }

    private static String renderLauncherSnippet(List<String> tokens) {
        return tokens.stream()
            .map(InterMedLauncher::quoteForDisplay)
            .collect(Collectors.joining(" ", "", System.lineSeparator()));
    }

    private static String renderShellWrapper(String argFileName) {
        return """
            #!/usr/bin/env sh
            set -eu
            SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
            JAVA_BIN="${JAVA:-java}"
            exec "$JAVA_BIN" @"$SCRIPT_DIR/%s" "$@"
            """.formatted(argFileName);
    }

    private static String renderWindowsWrapper(String argFileName) {
        return """
            @echo off
            setlocal
            set "SCRIPT_DIR=%%~dp0"
            set "JAVA_BIN=%%JAVA%%"
            if not defined JAVA_BIN set "JAVA_BIN=java"
            "%%JAVA_BIN%%" @"%%SCRIPT_DIR%%%s" %%*
            """.formatted(argFileName).replace("\n", "\r\n");
    }

    private static void writeLaunchKitReadme(Path outputDir,
                                             LaunchPaths paths,
                                             List<LaunchKitVariant> variants,
                                             boolean includesMappings) {
        String availableProfiles = variants.stream()
            .map(LaunchKitVariant::profile)
            .collect(Collectors.joining(", "));
        boolean hasFabricVariant = variants.stream().anyMatch(variant -> "fabric".equals(variant.profile()));
        String fabricNote = variants.stream().anyMatch(variant -> "fabric".equals(variant.profile()))
            ? "Use the `fabric` files for Fabric launcher profiles; use `generic` for Forge, NeoForge, vanilla servers, and mixed-runtime launch wrappers."
            : "Only the generic runtime variant is available in this kit; add a sibling `InterMedCore-*-fabric.jar` next to the generator jar if you also want Fabric-specific launch files.";
        String mappingsNote = includesMappings
            ? "This kit staged a mappings file into `runtime/mappings.tiny`, so the generated launch args keep remapping parity outside the development tree."
            : "No mappings file was staged into this kit; runtime remapping will use the default in-jar/runtime fallback path.";
        String launcherFieldLine = hasFabricVariant
            ? "   Open `launcher-jvm-args-generic.txt` (or the `fabric` variant when present)\n"
              + "   and paste the single line into the launcher's JVM arguments / Java flags field."
            : "   Open `launcher-jvm-args-generic.txt` and paste the single line into\n"
              + "   the launcher's JVM arguments / Java flags field.";
        String shellExamples = hasFabricVariant
            ? "2. Linux/macOS direct launch:\n"
              + "   `./intermed-launch-generic.sh -jar minecraft_server.jar nogui`\n"
              + "   or `./intermed-launch-fabric.sh -jar fabric-server-launch.jar nogui`\n"
            : "2. Linux/macOS direct launch:\n"
              + "   `./intermed-launch-generic.sh -jar minecraft_server.jar nogui`\n";
        String windowsExamples = hasFabricVariant
            ? "3. Windows direct launch:\n"
              + "   `intermed-launch-generic.cmd -jar minecraft_server.jar nogui`\n"
              + "   or `intermed-launch-fabric.cmd -jar fabric-server-launch.jar nogui`\n"
            : "3. Windows direct launch:\n"
              + "   `intermed-launch-generic.cmd -jar minecraft_server.jar nogui`\n";
        String fabricWrapperLine = hasFabricVariant
            ? "\nUse the `fabric` wrapper variants for Fabric-specific launch profiles.\n"
            : "\n";
        String readme = """
            InterMed Launch Kit
            ===================

            Game directory: %s
            Mods directory: %s
            Profiles in this kit: %s

            %s

            %s

            Use this folder in one of three ways:

            1. Any launcher with a JVM arguments field:
            %s

            %s
            %s
            %s
            The wrappers forward every additional argument directly to Java.
            Examples:

              intermed-launch-generic.sh -jar minecraft_server.jar nogui
              intermed-launch-generic.cmd -cp forge-bootstrap.jar cpw.mods.bootstraplauncher.BootstrapLauncher --launchTarget forgeclient

            If your launcher UI tokenizes quoted JVM flags strangely, use the generated
            shell / cmd wrapper instead of manually pasting flags.
            """.formatted(paths.gameDir(), paths.modsDir(), availableProfiles, fabricNote, mappingsNote,
            launcherFieldLine, shellExamples, windowsExamples, fabricWrapperLine);
        writeUtf8(outputDir.resolve("README.txt"), readme);
    }

    private static void writeLaunchKitManifest(Path outputDir,
                                               LaunchPaths paths,
                                               List<LaunchKitVariant> variants,
                                               Path stagedMappings) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", "intermed-launch-kit-v1");
        root.addProperty("generatedAtUtc", Instant.now().toString());
        root.addProperty("gameDir", paths.gameDir().toString());
        root.addProperty("modsDir", paths.modsDir().toString());
        if (stagedMappings != null) {
            root.addProperty("mappings", stagedMappings.toString());
        }
        JsonArray variantArray = new JsonArray();
        for (LaunchKitVariant variant : variants) {
            JsonObject item = new JsonObject();
            item.addProperty("profile", variant.profile());
            item.addProperty("agent", variant.agent().toString());
            item.addProperty("bootstrap", variant.bootstrap().toString());
            item.addProperty("argFile", variant.argFile().toString());
            item.addProperty("launcherSnippet", variant.launcherSnippet().toString());
            item.addProperty("shellScript", variant.shellScript().toString());
            item.addProperty("cmdScript", variant.cmdScript().toString());
            JsonArray args = new JsonArray();
            variant.jvmArgs().forEach(args::add);
            item.add("jvmArgs", args);
            variantArray.add(item);
        }
        root.add("variants", variantArray);
        writeUtf8(
            outputDir.resolve("intermed-launch-kit.json"),
            new GsonBuilder().setPrettyPrinting().create().toJson(root) + System.lineSeparator()
        );
    }

    private static void writeUtf8(Path output, String content) {
        try {
            Files.createDirectories(output.toAbsolutePath().normalize().getParent());
            Files.writeString(output, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write " + output, e);
        }
    }

    private static void makeExecutableIfSupported(Path path) {
        try {
            Files.setPosixFilePermissions(path, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            ));
        } catch (UnsupportedOperationException ignored) {
            // Windows / non-POSIX file systems do not expose POSIX permissions.
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mark launch-kit script executable: " + path, e);
        }
    }

    private static Path resolvePerformanceSnapshot(LaunchRequest request, Path projectRoot) {
        if (request.option("performance-snapshot").isPresent()) {
            Path explicit = Path.of(request.option("performance-snapshot").get()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(explicit)) {
                throw new IllegalArgumentException("Performance snapshot not found: " + explicit);
            }
            return explicit;
        }
        Path defaultSnapshot = projectRoot
            .resolve("app/build/reports/performance/alpha-performance-snapshot.json")
            .toAbsolutePath()
            .normalize();
        return Files.isRegularFile(defaultSnapshot) ? defaultSnapshot : null;
    }

    private record LaunchPaths(Path gameDir, Path modsDir, Path configDir) {}

    private record LaunchKitVariant(String profile,
                                    Path agent,
                                    Path bootstrap,
                                    Path argFile,
                                    Path launcherSnippet,
                                    Path shellScript,
                                    Path cmdScript,
                                    List<String> jvmArgs) {}

    static final class LaunchRequest {
        private final String command;
        private final Map<String, List<String>> options;
        private final List<String> passthrough;

        private LaunchRequest(String command, Map<String, List<String>> options, List<String> passthrough) {
            this.command = command;
            this.options = options;
            this.passthrough = passthrough;
        }

        String command() {
            return command;
        }

        Optional<String> option(String key) {
            List<String> values = options.get(key);
            if (values == null || values.isEmpty()) {
                return Optional.empty();
            }
            return Optional.ofNullable(values.get(values.size() - 1));
        }

        boolean hasFlag(String key) {
            return option(key).map("true"::equalsIgnoreCase).orElse(false);
        }

        List<String> passthrough() {
            return passthrough;
        }
    }

    static final class DoctorReport {
        private final List<String> lines;
        private final boolean hasErrors;

        private DoctorReport(List<String> lines, boolean hasErrors) {
            this.lines = List.copyOf(lines);
            this.hasErrors = hasErrors;
        }

        List<String> lines() {
            return lines;
        }

        boolean hasErrors() {
            return hasErrors;
        }
    }
}
