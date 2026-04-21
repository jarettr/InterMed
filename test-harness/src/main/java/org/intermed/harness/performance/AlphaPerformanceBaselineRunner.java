package org.intermed.harness.performance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.harness.HarnessConfig;
import org.intermed.harness.bootstrap.FabricServerInstaller;
import org.intermed.harness.bootstrap.ForgeServerInstaller;
import org.intermed.harness.bootstrap.VanillaServerFetcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Captures a small, comparable alpha performance baseline without running the
 * compatibility corpus. Each lane is a clean dedicated-server boot with JFR and
 * GC logging enabled.
 */
public final class AlphaPerformanceBaselineRunner {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String DONE_MARKER = "Done (";
    private static final int LOG_CAPTURE_LIMIT = 1024 * 1024;
    private static final long TICK_SMOKE_MILLIS = 8_000L;
    private static final Pattern DEBUG_TICKS = Pattern.compile(
        "Stopped (?:debug|tick) profiling after ([0-9.]+) seconds and ([0-9]+) ticks \\(([0-9.]+) ticks per second\\)");
    private static final Pattern GC_PAUSE = Pattern.compile("Pause[^\\n]*?\\b([0-9]+(?:\\.[0-9]+)?)ms");
    private static final Pattern HEAP_KIB = Pattern.compile("heap\\s+total\\s+(\\d+)K,\\s+used\\s+(\\d+)K",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern METASPACE_KIB = Pattern.compile("Metaspace\\s+used\\s+(\\d+)K,\\s+committed\\s+(\\d+)K",
        Pattern.CASE_INSENSITIVE);

    private final HarnessConfig config;

    public AlphaPerformanceBaselineRunner(HarnessConfig config) {
        this.config = config;
    }

    public Path run() throws Exception {
        ensureBaseServers();

        Path outputDir = reportDir();
        Path artifactsDir = outputDir.resolve("artifacts");
        Files.createDirectories(artifactsDir);

        JsonArray lanes = new JsonArray();
        lanes.add(runLane(new LaneSpec("native-fabric-clean", "native-clean", "fabric", false, artifactsDir)));
        lanes.add(runLane(new LaneSpec("native-forge-clean", "native-clean", "forge", false, artifactsDir)));
        lanes.add(runLane(new LaneSpec("intermed-attached-fabric-clean", "intermed-attached", "fabric", true, artifactsDir)));

        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("schema", "intermed-alpha-performance-snapshot-v1");
        snapshot.addProperty("generatedAt", Instant.now().toString());
        snapshot.add("environment", environment());
        snapshot.add("claimGuardrail", claimGuardrail(lanes));
        snapshot.add("lanes", lanes);
        snapshot.add("internalHotPathEvidence", internalHotPathEvidence());

        Path snapshotPath = outputDir.resolve("alpha-performance-snapshot.json");
        Path baselinePath = outputDir.resolve("native-loader-baseline.json");
        Files.writeString(snapshotPath, GSON.toJson(snapshot), StandardCharsets.UTF_8);
        Files.writeString(baselinePath, GSON.toJson(snapshot), StandardCharsets.UTF_8);
        System.out.println("[Performance] Initial alpha performance snapshot: " + snapshotPath.toAbsolutePath());
        System.out.println("[Performance] Native baseline mirror: " + baselinePath.toAbsolutePath());
        return snapshotPath;
    }

    private void ensureBaseServers() throws Exception {
        Files.createDirectories(config.cacheDir());
        Files.createDirectories(config.modsCache());
        new VanillaServerFetcher().fetch(config.mcVersion, config.cacheDir());
        new FabricServerInstaller().install(
            config.mcVersion,
            config.cacheDir(),
            config.serverBaseFabric(),
            config.javaExecutable
        );
        new ForgeServerInstaller().install(
            config.mcVersion,
            config.forgeVersion,
            config.cacheDir(),
            config.serverBaseForge(),
            config.javaExecutable
        );
        writeCommonServerFiles(config.serverBaseFabric());
        writeCommonServerFiles(config.serverBaseForge());
    }

    private JsonObject runLane(LaneSpec lane) {
        Path runDir = config.outputDir.resolve("performance-baseline").resolve("runs").resolve(lane.name());
        try {
            deleteRecursively(runDir);
            Files.createDirectories(runDir);
            Path baseDir = lane.loader().equals("forge") ? config.serverBaseForge() : config.serverBaseFabric();
            copyRecursively(baseDir, runDir);
            writeCommonServerFiles(runDir);
            return executeLane(lane, runDir);
        } catch (Exception e) {
            JsonObject failed = laneBase(lane, "fail");
            failed.addProperty("reason", e.getMessage());
            failed.add("measures", measureStatuses("not-observed"));
            failed.add("artifacts", artifactPaths(lane, runDir, lane.artifactsDir()));
            return failed;
        } finally {
            try {
                deleteRecursively(runDir);
            } catch (IOException ignored) {
                // Run directories are transient; artifacts are copied out before cleanup.
            }
        }
    }

    private JsonObject executeLane(LaneSpec lane, Path runDir) throws Exception {
        Path jfr = lane.artifactsDir().resolve(lane.name() + ".jfr").toAbsolutePath();
        Path gcLog = lane.artifactsDir().resolve(lane.name() + "-gc.log").toAbsolutePath();
        Path serverLog = lane.artifactsDir().resolve(lane.name() + "-server.log").toAbsolutePath();
        Path heapInfo = lane.artifactsDir().resolve(lane.name() + "-heap-info.txt").toAbsolutePath();
        Path metaspaceInfo = lane.artifactsDir().resolve(lane.name() + "-metaspace-info.txt").toAbsolutePath();

        deleteExistingLaneArtifacts(lane);
        List<String> command = buildCommand(lane, runDir, jfr, gcLog);
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(runDir.toFile());
        pb.redirectErrorStream(true);

        StringBuilder log = new StringBuilder();
        CountDownLatch doneSeen = new CountDownLatch(1);
        long[] startupMs = {0L};
        long start = System.currentTimeMillis();
        Process process = pb.start();

        Thread reader = new Thread(() -> drainProcessOutput(process, log, doneSeen, startupMs, start),
            "alpha-performance-" + lane.name());
        reader.setDaemon(true);
        reader.start();

        boolean booted = doneSeen.await(config.timeoutSeconds, TimeUnit.SECONDS);
        if (!booted) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            reader.join(2_000L);
            Files.writeString(serverLog, log.toString(), StandardCharsets.UTF_8);
            JsonObject timeout = laneBase(lane, "fail-timeout");
            timeout.addProperty("reason", "Dedicated server did not reach startup marker before timeout.");
            timeout.add("measures", measureStatuses("not-observed"));
            timeout.add("artifacts", artifactPaths(lane, runDir, lane.artifactsDir()));
            return timeout;
        }

        try (OutputStreamWriter stdin = new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)) {
            stdin.write("debug start\n");
            stdin.flush();
            Thread.sleep(TICK_SMOKE_MILLIS);
            stdin.write("debug stop\n");
            stdin.flush();
            Thread.sleep(2_000L);
            writeJcmd(process.pid(), "GC.heap_info", heapInfo);
            writeJcmd(process.pid(), "GC.heap_info", metaspaceInfo);
            stdin.write("stop\n");
            stdin.flush();
        } catch (IOException ignored) {
            // Some loaders close stdin while shutting down; the lane can still
            // report startup/JFR/GC evidence.
        }

        boolean stopped = process.waitFor(30, TimeUnit.SECONDS);
        if (!stopped) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
        }
        reader.join(3_000L);
        Files.writeString(serverLog, log.toString(), StandardCharsets.UTF_8);

        JsonObject laneJson = laneBase(lane, process.exitValue() == 0 ? "pass" : "pass-stopped-for-smoke");
        laneJson.addProperty("reason", "Dedicated server reached startup marker and completed short tick smoke.");
        laneJson.add("measures", measures(startupMs[0], jfr, gcLog, heapInfo, metaspaceInfo, log.toString()));
        laneJson.add("artifacts", artifactPaths(lane, runDir, lane.artifactsDir()));
        return laneJson;
    }

    private List<String> buildCommand(LaneSpec lane, Path runDir, Path jfr, Path gcLog) {
        List<String> cmd = new ArrayList<>();
        cmd.add(config.javaExecutable);
        cmd.add("-Xmx" + Math.max(512, config.heapMb) + "m");
        cmd.add("-Xms128m");
        cmd.add("-XX:+UseG1GC");
        cmd.add("-Xlog:gc*:file=" + gcLog + ":time,uptime,level,tags");
        cmd.add("-XX:StartFlightRecording=filename=" + jfr
            + ",settings=profile,dumponexit=true,disk=true");
        if (lane.intermedAttached()) {
            Path agent = resolveAgentJar(lane.loader());
            cmd.add("-javaagent:" + agent.toAbsolutePath());
            cmd.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
            cmd.add("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
            cmd.add("-Druntime.game.dir=" + runDir.toAbsolutePath());
            cmd.add("-Druntime.env=server");
            cmd.add("-Dintermed.modsDir=" + runDir.resolve("intermed_mods").toAbsolutePath());
            try {
                Files.createDirectories(runDir.resolve("intermed_mods"));
                Files.createDirectories(runDir.resolve("config"));
                Files.writeString(runDir.resolve("config/intermed-runtime.properties"),
                    "aot.cache.enabled=true\n"
                    + "security.strict.mode=false\n"
                    + "security.legacy.broad.permissions.enabled=true\n"
                    + "runtime.env=server\n",
                    StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        if (lane.loader().equals("forge")) {
            Path args = findUnixArgsFile(runDir);
            if (args != null) {
                cmd.add("@" + args.toAbsolutePath());
            } else {
                cmd.add("-jar");
                cmd.add(findForgeJar(runDir));
            }
        } else {
            cmd.add("-jar");
            cmd.add(runDir.resolve("fabric-server-launch.jar").toAbsolutePath().toString());
        }
        cmd.add("nogui");
        return cmd;
    }

    private void drainProcessOutput(Process process,
                                    StringBuilder log,
                                    CountDownLatch doneSeen,
                                    long[] startupMs,
                                    long startMs) {
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (log.length() < LOG_CAPTURE_LIMIT) {
                    log.append(line).append('\n');
                }
                if (startupMs[0] == 0L && line.contains(DONE_MARKER)) {
                    startupMs[0] = System.currentTimeMillis() - startMs;
                    doneSeen.countDown();
                }
            }
        } catch (IOException ignored) {
            // The process stream closes during forced cleanup.
        }
    }

    private JsonObject measures(long startupMs,
                                Path jfr,
                                Path gcLog,
                                Path heapInfo,
                                Path metaspaceInfo,
                                String log) throws IOException {
        JsonObject measures = new JsonObject();
        measures.add("startupTime", millisMeasure("observed", startupMs));
        measures.add("heap", heapMeasure(heapInfo));
        measures.add("metaspace", metaspaceMeasure(metaspaceInfo));
        measures.add("gcPauses", gcPauseMeasure(gcLog));
        measures.add("tickTime", tickMeasure(log));
        measures.add("jfrDump", fileMeasure("observed", jfr));
        return measures;
    }

    private JsonObject heapMeasure(Path heapInfo) {
        String raw = readIfExists(heapInfo);
        JsonObject measure = new JsonObject();
        Matcher matcher = HEAP_KIB.matcher(raw);
        if (matcher.find()) {
            measure.addProperty("status", "observed");
            measure.addProperty("committedBytes", kibToBytes(matcher.group(1)));
            measure.addProperty("usedBytes", kibToBytes(matcher.group(2)));
        } else {
            measure.addProperty("status", raw.isBlank() ? "not-observed" : "raw-only");
        }
        measure.addProperty("artifact", heapInfo.toString());
        return measure;
    }

    private JsonObject metaspaceMeasure(Path metaspaceInfo) {
        String raw = readIfExists(metaspaceInfo);
        JsonObject measure = new JsonObject();
        Matcher matcher = METASPACE_KIB.matcher(raw);
        if (matcher.find()) {
            measure.addProperty("status", "observed");
            measure.addProperty("usedBytes", kibToBytes(matcher.group(1)));
            measure.addProperty("committedBytes", kibToBytes(matcher.group(2)));
        } else {
            measure.addProperty("status", raw.isBlank() ? "not-observed" : "raw-only");
        }
        measure.addProperty("artifact", metaspaceInfo.toString());
        return measure;
    }

    private JsonObject gcPauseMeasure(Path gcLog) throws IOException {
        String raw = readIfExists(gcLog);
        Matcher matcher = GC_PAUSE.matcher(raw);
        int count = 0;
        double total = 0.0d;
        double max = 0.0d;
        while (matcher.find()) {
            double millis = Double.parseDouble(matcher.group(1));
            count++;
            total += millis;
            max = Math.max(max, millis);
        }
        JsonObject measure = new JsonObject();
        measure.addProperty("status", Files.isRegularFile(gcLog) ? "observed" : "not-observed");
        measure.addProperty("pauseEventCount", count);
        measure.addProperty("pauseTotalMillis", round(total));
        measure.addProperty("pauseMaxMillis", round(max));
        measure.addProperty("artifact", gcLog.toString());
        return measure;
    }

    private JsonObject tickMeasure(String log) {
        JsonObject measure = new JsonObject();
        Matcher matcher = DEBUG_TICKS.matcher(log);
        if (matcher.find()) {
            double seconds = Double.parseDouble(matcher.group(1));
            long ticks = Long.parseLong(matcher.group(2));
            double tps = Double.parseDouble(matcher.group(3));
            measure.addProperty("status", "observed");
            measure.addProperty("profilingSeconds", round(seconds));
            measure.addProperty("ticks", ticks);
            measure.addProperty("ticksPerSecond", round(tps));
            measure.addProperty("millisPerTick", ticks <= 0 ? 0.0d : round(seconds * 1000.0d / ticks));
        } else {
            measure.addProperty("status", "not-observed");
            measure.addProperty("reason", "Server did not emit debug profiling summary during the short smoke.");
        }
        return measure;
    }

    private JsonObject artifactPaths(LaneSpec lane, Path runDir, Path artifactsDir) {
        JsonObject artifacts = new JsonObject();
        artifacts.addProperty("serverLog", artifactsDir.resolve(lane.name() + "-server.log").toString());
        artifacts.addProperty("gcLog", artifactsDir.resolve(lane.name() + "-gc.log").toString());
        artifacts.addProperty("jfrDump", artifactsDir.resolve(lane.name() + ".jfr").toString());
        artifacts.addProperty("heapInfo", artifactsDir.resolve(lane.name() + "-heap-info.txt").toString());
        artifacts.addProperty("metaspaceInfo", artifactsDir.resolve(lane.name() + "-metaspace-info.txt").toString());
        artifacts.addProperty("transientRunDir", runDir.toString());
        return artifacts;
    }

    private void deleteExistingLaneArtifacts(LaneSpec lane) throws IOException {
        if (!Files.isDirectory(lane.artifactsDir())) {
            return;
        }
        try (var stream = Files.list(lane.artifactsDir())) {
            for (Path path : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith(lane.name()))
                    .toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private JsonObject fileMeasure(String status, Path path) throws IOException {
        JsonObject measure = new JsonObject();
        measure.addProperty("status", Files.isRegularFile(path) && Files.size(path) > 0L ? status : "not-observed");
        measure.addProperty("artifact", path.toString());
        measure.addProperty("bytes", Files.isRegularFile(path) ? Files.size(path) : 0L);
        return measure;
    }

    private JsonObject millisMeasure(String status, long millis) {
        JsonObject measure = new JsonObject();
        measure.addProperty("status", status);
        measure.addProperty("millis", Math.max(0L, millis));
        return measure;
    }

    private JsonObject measureStatuses(String status) {
        JsonObject measures = new JsonObject();
        for (String key : List.of("startupTime", "heap", "metaspace", "gcPauses", "tickTime", "jfrDump")) {
            JsonObject value = new JsonObject();
            value.addProperty("status", status);
            measures.add(key, value);
        }
        return measures;
    }

    private JsonObject laneBase(LaneSpec lane, String status) {
        JsonObject json = new JsonObject();
        json.addProperty("name", lane.name());
        json.addProperty("type", lane.type());
        json.addProperty("loader", lane.loader());
        json.addProperty("status", status);
        json.addProperty("intermedAttached", lane.intermedAttached());
        json.addProperty("heapMb", Math.max(512, config.heapMb));
        json.addProperty("tickSmokeMillis", TICK_SMOKE_MILLIS);
        return json;
    }

    private JsonObject claimGuardrail(JsonArray lanes) {
        boolean complete = true;
        for (var lane : lanes) {
            JsonObject object = lane.getAsJsonObject();
            String status = object.get("status").getAsString();
            complete &= status.equals("pass") || status.equals("pass-stopped-for-smoke");
        }
        JsonObject guardrail = new JsonObject();
        guardrail.addProperty("title", "Initial alpha performance snapshot");
        guardrail.addProperty("finalOverheadTargetClaimed", false);
        guardrail.addProperty("nativeBaselineComplete", complete);
        guardrail.addProperty("baselineKind", "dedicated-server-short-smoke");
        guardrail.addProperty("notes",
            "This is an initial alpha performance snapshot with clean native Fabric/Forge and InterMed-attached lanes. It is not a final 10-15% overhead claim.");
        return guardrail;
    }

    private JsonObject environment() {
        JsonObject env = new JsonObject();
        env.addProperty("minecraft", config.mcVersion);
        env.addProperty("forge", config.forgeVersion);
        env.addProperty("java", config.javaExecutable);
        env.addProperty("heapMb", Math.max(512, config.heapMb));
        env.addProperty("timeoutSeconds", config.timeoutSeconds);
        return env;
    }

    private JsonArray internalHotPathEvidence() {
        JsonArray evidence = new JsonArray();
        evidence.add(hotPathArtifact("registry", "app/build/reports/microbench/registry-hot-path.txt"));
        evidence.add(hotPathArtifact("remapper", "app/build/reports/microbench/remapper-hot-path.txt"));
        evidence.add(hotPathArtifact("event-bus", "app/build/reports/microbench/event-bus-hot-path.txt"));
        return evidence;
    }

    private JsonObject hotPathArtifact(String name, String pathText) {
        Path path = Path.of(pathText);
        JsonObject artifact = new JsonObject();
        artifact.addProperty("name", name);
        artifact.addProperty("path", pathText);
        artifact.addProperty("status", Files.isRegularFile(path) ? "present" : "missing");
        artifact.addProperty("interpretation", "Internal hot-path evidence only; not real modpack overhead.");
        return artifact;
    }

    private Path reportDir() throws IOException {
        String configured = System.getProperty("intermed.performance.outputDir");
        Path dir;
        if (configured != null && !configured.isBlank()) {
            dir = Path.of(configured);
        } else {
            dir = defaultProjectRoot()
                .map(root -> root.resolve("app/build/reports/performance"))
                .orElse(config.reportDir().resolve("performance"));
        }
        Files.createDirectories(dir);
        return dir.toAbsolutePath().normalize();
    }

    private Optional<Path> defaultProjectRoot() {
        Path cwd = Path.of("").toAbsolutePath().normalize();
        if (Files.isRegularFile(cwd.resolve("settings.gradle.kts")) && Files.isDirectory(cwd.resolve("app"))) {
            return Optional.of(cwd);
        }
        Path parent = cwd.getParent();
        if (parent != null
                && Files.isRegularFile(parent.resolve("settings.gradle.kts"))
                && Files.isDirectory(parent.resolve("app"))) {
            return Optional.of(parent);
        }
        return Optional.empty();
    }

    private Path resolveAgentJar(String loader) {
        if (!loader.equals("fabric")) {
            return config.intermedJar;
        }
        String fileName = config.intermedJar.getFileName().toString();
        String fabricVariant = fileName.endsWith(".jar")
            ? fileName.substring(0, fileName.length() - 4) + "-fabric.jar"
            : fileName + "-fabric.jar";
        Path sibling = config.intermedJar.resolveSibling(fabricVariant);
        return Files.exists(sibling) ? sibling : config.intermedJar;
    }

    private Path findUnixArgsFile(Path dir) {
        Path libraries = dir.resolve("libraries");
        if (!Files.isDirectory(libraries)) {
            return null;
        }
        try (var stream = Files.walk(libraries)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().equals("unix_args.txt"))
                .findFirst()
                .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String findForgeJar(Path dir) {
        try (var stream = Files.list(dir)) {
            return stream
                .filter(p -> p.getFileName().toString().startsWith("forge"))
                .filter(p -> p.getFileName().toString().endsWith(".jar"))
                .map(Path::toAbsolutePath)
                .map(Path::toString)
                .findFirst()
                .orElse("server.jar");
        } catch (IOException e) {
            return "server.jar";
        }
    }

    private void writeJcmd(long pid, String command, Path output) {
        List<String> cmd = List.of(resolveJcmd(), Long.toString(pid), command);
        try {
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String text = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor(10, TimeUnit.SECONDS);
            Files.writeString(output, text, StandardCharsets.UTF_8);
        } catch (Exception e) {
            try {
                Files.writeString(output, "jcmd failed: " + e.getMessage(), StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Best-effort diagnostics.
            }
        }
    }

    private String resolveJcmd() {
        Path java = Path.of(config.javaExecutable);
        Path sibling = java.getParent() == null ? null : java.getParent().resolve("jcmd");
        if (sibling != null && Files.isExecutable(sibling)) {
            return sibling.toString();
        }
        return "jcmd";
    }

    private static void writeCommonServerFiles(Path dir) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("server.properties"),
            "online-mode=false\n"
            + "server-port=0\n"
            + "level-type=flat\n"
            + "generate-structures=false\n"
            + "spawn-monsters=false\n"
            + "spawn-npcs=false\n"
            + "spawn-animals=false\n"
            + "view-distance=2\n"
            + "simulation-distance=2\n",
            StandardCharsets.UTF_8);
    }

    private static void copyRecursively(Path source, Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(path -> {
                try {
                    Path dest = target.resolve(source.relativize(path));
                    if (Files.isDirectory(path)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(path, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            });
        }
    }

    private static String readIfExists(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.readString(path, StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static long kibToBytes(String value) {
        return Long.parseLong(value) * 1024L;
    }

    private static double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }

    private record LaneSpec(String name, String type, String loader, boolean intermedAttached, Path artifactsDir) {}
}
