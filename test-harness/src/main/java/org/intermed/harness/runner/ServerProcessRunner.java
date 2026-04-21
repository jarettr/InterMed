package org.intermed.harness.runner;

import org.intermed.harness.HarnessConfig;
import org.intermed.harness.analysis.IssueRecord;
import org.intermed.harness.analysis.LogAnalyzer;
import org.intermed.harness.discovery.ModCandidate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Runs a single {@link TestCase} as a separate JVM process and produces a
 * {@link TestResult}.
 *
 * <p>Execution model:
 * <ol>
 *   <li>Create an isolated run directory under {@code config.runsDir()}.</li>
 *   <li>Symlink (or copy) the mod JARs from the cache into {@code intermed_mods/}.</li>
 *   <li>Copy the appropriate server base (Forge/Fabric) into the run dir.</li>
 *   <li>Write {@code intermed-runtime.properties} tuned for testing (fast start, bridge policy).</li>
 *   <li>Launch the server with InterMed as javaagent; capture stdout+stderr.</li>
 *   <li>Monitor output for success / failure patterns up to the configured timeout.</li>
 *   <li>Return the result and optionally clean up.</li>
 * </ol>
 */
public final class ServerProcessRunner {

    private static final AtomicInteger NEXT_PORT = new AtomicInteger(25_000);

    /** Log line that indicates a successful Minecraft server startup. */
    private static final String DONE_MARKER = "Done (";

    /** Max captured log size per run before truncation (bytes). */
    private static final int MAX_LOG_BYTES = 1024 * 64; // 64 KB

    private final HarnessConfig config;
    private final LogAnalyzer analyzer;

    public ServerProcessRunner(HarnessConfig config) {
        this.config   = config;
        this.analyzer = new LogAnalyzer();
    }

    /**
     * Executes the given test case synchronously and returns the result.
     * This method is safe to call from multiple threads concurrently.
     */
    public TestResult run(TestCase testCase) {
        Instant start = Instant.now();
        Path runDir = config.runsDir().resolve(testCase.id());

        StringBuilder logBuffer = new StringBuilder();
        int exitCode = -1;

        try {
            setupRunDir(runDir, testCase);
            List<String> cmd = buildCommand(runDir, testCase);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(runDir.toFile());
            pb.redirectErrorStream(true);

            Process proc = pb.start();
            long startMs = System.currentTimeMillis();
            long timeoutMs = config.timeoutSeconds * 1000L;

            // Read output in the current thread (small timeout = no need for a separate reader thread)
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()))) {

                String line;
                boolean timedOut = false;

                while ((line = reader.readLine()) != null) {
                    appendLine(logBuffer, line);

                    // Check success marker
                    if (line.contains(DONE_MARKER)) {
                        long startupMs = System.currentTimeMillis() - startMs;
                        // Drain briefly then stop
                        drainQuietly(reader, 2000);
                        proc.destroyForcibly();
                        proc.waitFor(5, TimeUnit.SECONDS);
                        exitCode = 0;

                        String rawLog = logBuffer.toString();
                        List<IssueRecord> issues = analyzer.analyze(rawLog);
                        TestResult.Outcome outcome = classifyOutcome(issues, startupMs, exitCode, false);

                        persistLog(runDir, rawLog);
                        return new TestResult(testCase, outcome, startupMs, exitCode,
                            rawLog, issues, start);
                    }

                    // Check early fatal patterns
                    if (analyzer.isFatalLine(line)) {
                        // Give the process 3 more seconds to write a full stack trace
                        drainQuietly(reader, 3000);
                        proc.destroyForcibly();
                        proc.waitFor(5, TimeUnit.SECONDS);
                        exitCode = proc.exitValue();
                        break;
                    }

                    // Timeout check (done inside the read loop so we don't block on read())
                    if (System.currentTimeMillis() - startMs > timeoutMs) {
                        timedOut = true;
                        proc.destroyForcibly();
                        proc.waitFor(5, TimeUnit.SECONDS);
                        exitCode = -1;
                        break;
                    }
                }

                if (proc.isAlive()) {
                    proc.destroyForcibly();
                    proc.waitFor(5, TimeUnit.SECONDS);
                    exitCode = -1;
                }

                String rawLog = logBuffer.toString();
                List<IssueRecord> issues = analyzer.analyze(rawLog);

                boolean actualTimeout = exitCode == -1
                    && !rawLog.contains(DONE_MARKER)
                    && (System.currentTimeMillis() - startMs) >= timeoutMs;
                TestResult.Outcome outcome = classifyOutcome(issues, 0, exitCode, actualTimeout);

                persistLog(runDir, rawLog);
                return new TestResult(testCase, outcome, 0, exitCode, rawLog, issues, start);

            }

        } catch (IOException | InterruptedException e) {
            String rawLog = logBuffer.toString();
            List<IssueRecord> issues = List.of(new IssueRecord(
                IssueRecord.Severity.FATAL, "HARNESS_ERROR",
                "Test harness error: " + e.getMessage(), ""));
            return new TestResult(testCase, TestResult.Outcome.FAIL_OTHER,
                0, -1, rawLog, issues, start);
        }
    }

    // ── directory setup ────────────────────────────────────────────────────────

    private void setupRunDir(Path runDir, TestCase testCase) throws IOException {
        // Clean any previous run for this test case
        deleteRecursively(runDir);
        Files.createDirectories(runDir);

        // Copy base server (Forge or Fabric) into run dir
        Path baseDir = switch (testCase.loader()) {
            case FORGE -> config.serverBaseForge();
            case FABRIC -> config.serverBaseFabric();
            case NEOFORGE -> config.serverBaseNeoForge();
        };

        copyBaseServer(baseDir, runDir);

        // Place mods in intermed_mods/
        Path modsDir = runDir.resolve("intermed_mods");
        Files.createDirectories(modsDir);
        for (ModCandidate mod : testCase.mods()) {
            Path src = config.modsCache().resolve("jars").resolve(mod.fileName());
            if (Files.exists(src)) {
                Path dest = modsDir.resolve(mod.fileName());
                try {
                    Files.createSymbolicLink(dest, src);
                } catch (UnsupportedOperationException | IOException e) {
                    Files.copy(src, dest);
                }
            }
        }

        // Write InterMed config tuned for testing
        Path configDir = runDir.resolve("config");
        Files.createDirectories(configDir);
        Files.writeString(configDir.resolve("intermed-runtime.properties"),
            "aot.cache.enabled=true\n"
            + "mixin.conflict.policy=bridge\n"
            + "mixin.ast.reclaim.enabled=true\n"
            + "security.strict.mode=false\n"
            + "security.legacy.broad.permissions.enabled=true\n"
            + "vfs.enabled=true\n"
            + "vfs.conflict.policy=merge_then_priority\n"
            + "sandbox.espresso.enabled=false\n"
            + "sandbox.wasm.enabled=false\n"
            + "runtime.env=server\n"
            + "resolver.allow.fallback=true\n"
        );

        // eula + per-run server.properties
        Files.writeString(runDir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(runDir.resolve("server.properties"), buildServerProperties());
    }

    private void copyBaseServer(Path src, Path dest) throws IOException {
        // Only copy the top-level files and the libraries dir; avoid copying existing world data
        if (!Files.exists(src)) {
            throw new IOException("Server base directory not found: " + src
                + " — run 'bootstrap' first");
        }
        try (var stream = Files.walk(src)) {
            stream.forEach(s -> {
                try {
                    Path d = dest.resolve(src.relativize(s));
                    if (Files.isDirectory(s)) {
                        Files.createDirectories(d);
                    } else {
                        Files.copy(s, d, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    // ── command building ───────────────────────────────────────────────────────

    private List<String> buildCommand(Path runDir, TestCase testCase) {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.javaExecutable);
        cmd.add("-Xmx" + config.heapMb + "m");
        cmd.add("-Xms512m");
        cmd.add("-XX:+UseZGC");
        cmd.add("-XX:+ZGenerational");

        // InterMed as javaagent
        Path agentJar = resolveAgentJar(testCase.loader());
        if (Files.exists(agentJar)) {
            cmd.add("-javaagent:" + agentJar.toAbsolutePath());
        }

        // Module opens required by InterMed
        cmd.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
        cmd.add("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");

        // Point InterMed to the run dir
        cmd.add("-Druntime.game.dir=" + runDir.toAbsolutePath());
        cmd.add("-Druntime.env=server");
        cmd.add("-Dintermed.modsDir=" + runDir.resolve("intermed_mods").toAbsolutePath());

        // Server JAR
        if (testCase.loader() == TestCase.Loader.FORGE || testCase.loader() == TestCase.Loader.NEOFORGE) {
            Path argsFile = findUnixArgsFile(runDir);
            if (argsFile != null) {
                cmd.add("@" + argsFile.toAbsolutePath());
            } else {
                cmd.add("-jar");
                cmd.add(findServerJar(runDir, testCase.loader() == TestCase.Loader.NEOFORGE ? "neo" : "forge"));
            }
        } else {
            cmd.add("-jar");
            cmd.add(runDir.resolve("fabric-server-launch.jar").toAbsolutePath().toString());
        }

        cmd.add("nogui");
        return cmd;
    }

    private Path resolveAgentJar(TestCase.Loader loader) {
        if (loader != TestCase.Loader.FABRIC) {
            return config.intermedJar;
        }
        String fileName = config.intermedJar.getFileName().toString();
        String fabricVariant = fileName.endsWith(".jar")
            ? fileName.substring(0, fileName.length() - 4) + "-fabric.jar"
            : fileName + "-fabric.jar";
        Path sibling = config.intermedJar.resolveSibling(fabricVariant);
        return Files.exists(sibling) ? sibling : config.intermedJar;
    }

    private String findServerJar(Path dir, String prefix) {
        try (var stream = Files.list(dir)) {
            java.util.List<String> prefixes = prefix.equals("neo")
                ? java.util.List.of("neoforge", "forge")
                : java.util.List.of(prefix);
            return stream
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return prefixes.stream().anyMatch(n::startsWith) && n.endsWith(".jar");
                })
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .findFirst()
                .orElse("server.jar");
        } catch (IOException e) {
            return "server.jar";
        }
    }

    private Path findUnixArgsFile(Path dir) {
        try (var stream = Files.walk(dir.resolve("libraries"))) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals("unix_args.txt"))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String buildServerProperties() {
        int uniquePort = allocateUniquePort();
        return "online-mode=false\n"
            + "server-port=" + uniquePort + "\n"
            + "level-type=flat\n"
            + "generate-structures=false\n"
            + "spawn-monsters=false\n"
            + "view-distance=2\n"
            + "simulation-distance=2\n";
    }

    private int allocateUniquePort() {
        for (int attempts = 0; attempts < 256; attempts++) {
            int candidate = NEXT_PORT.getAndIncrement();
            if (candidate > 45_000) {
                NEXT_PORT.compareAndSet(candidate + 1, 25_000);
                candidate = NEXT_PORT.getAndIncrement();
            }
            if (isPortBindable(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("Unable to allocate a free harness server port");
    }

    private boolean isPortBindable(int port) {
        try (ServerSocket socket = new ServerSocket(port)) {
            socket.setReuseAddress(true);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    // ── outcome classification ─────────────────────────────────────────────────

    private TestResult.Outcome classifyOutcome(List<IssueRecord> issues,
                                               long startupMs, int exitCode,
                                               boolean timedOut) {
        if (timedOut)  return TestResult.Outcome.FAIL_TIMEOUT;
        if (exitCode != 0) {
            // Check issue tags for specific failure types
            for (IssueRecord issue : issues) {
                if (issue.tag().equals("MIXIN_CONFLICT")) return TestResult.Outcome.FAIL_MIXIN;
                if (issue.tag().equals("CAPABILITY_DENIED")) return TestResult.Outcome.FAIL_CAPABILITY;
                if (issue.tag().equals("PORT_IN_USE")) return TestResult.Outcome.FAIL_OTHER;
                if (issue.tag().equals("CLASS_NOT_FOUND")) return TestResult.Outcome.FAIL_DEPENDENCY;
            }
            return TestResult.Outcome.FAIL_CRASH;
        }

        boolean hasBridge = issues.stream().anyMatch(i -> i.tag().equals("MIXIN_BRIDGE"));
        boolean hasPerf   = issues.stream().anyMatch(i -> i.tag().equals("PERF_WARN"))
                         || startupMs > 90_000;

        if (hasPerf)   return TestResult.Outcome.PERF_WARN;
        if (hasBridge) return TestResult.Outcome.PASS_BRIDGED;
        return TestResult.Outcome.PASS;
    }

    // ── utilities ─────────────────────────────────────────────────────────────

    private void appendLine(StringBuilder buf, String line) {
        if (buf.length() < MAX_LOG_BYTES) {
            buf.append(line).append('\n');
        }
    }

    private void drainQuietly(BufferedReader reader, long maxMs) {
        long deadline = System.currentTimeMillis() + maxMs;
        try {
            while (System.currentTimeMillis() < deadline && reader.ready()) {
                String line = reader.readLine();
                if (line == null) break;
            }
        } catch (IOException ignored) {}
    }

    private void persistLog(Path runDir, String rawLog) {
        try {
            Path logDir = runDir.resolve("logs");
            Files.createDirectories(logDir);
            Files.writeString(logDir.resolve("harness.log"), rawLog);
        } catch (IOException ignored) {}
    }

    private void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                  .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) {} });
        }
    }
}
