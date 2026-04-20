package org.intermed.launcher;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        List<String> command = new ArrayList<>();
        command.add(request.option("java").orElse(defaultJavaCommand()));
        command.add("-javaagent:" + request.option("agent").orElse(resolveDefaultAgentPath().toString()));
        command.add("-Druntime.game.dir=" + paths.gameDir());
        command.add("-Druntime.mods.dir=" + paths.modsDir());
        command.add("-Dintermed.modsDir=" + paths.modsDir());
        request.option("mappings").ifPresent(path -> command.add("-Dintermed.mappings.tiny=" + path));

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
        Path modsDir = Path.of(request.option("mods-dir").orElse(RuntimeConfig.get().getModsDir().toString()));
        return ModDiscovery.discoverJars(modsDir.toFile());
    }

    private static List<File> discoverCandidateArchives(LaunchRequest request) {
        Path modsDir = Path.of(request.option("mods-dir").orElse(RuntimeConfig.get().getModsDir().toString()));
        return ModDiscovery.discoverCandidateArchives(modsDir.toFile());
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
        Path builtJar = Path.of("app/build/libs/InterMedCore.jar").toAbsolutePath().normalize();
        if (Files.exists(builtJar)) {
            return builtJar;
        }
        return self;
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
        System.out.println("  sbom [--mods-dir PATH] [--game-dir PATH] [--output PATH]");
        System.out.println("  api-gap-matrix [--game-dir PATH] [--output PATH]");
        System.out.println("  launch-readiness-report [--project-root PATH] [--mods-dir PATH] [--game-dir PATH] [--harness-results PATH] [--output PATH]");
        System.out.println("  diagnostics-bundle [--mods-dir PATH] [--game-dir PATH] [--harness-results PATH] [--output PATH]");
        System.out.println("  launch (--jar PATH | --main-class CLASS [--classpath CP]) [--agent PATH] [--mods-dir PATH] [--game-dir PATH] [--mappings PATH] [--harness-results PATH] [--dry-run] [--diagnostics-output PATH] [--no-diagnostics-bundle] [-- <minecraft args...>]");
    }

    private record LaunchPaths(Path gameDir, Path modsDir, Path configDir) {}

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
