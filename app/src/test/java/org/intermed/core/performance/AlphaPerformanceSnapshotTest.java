package org.intermed.core.performance;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.monitor.MetricsRegistry;
import org.intermed.core.monitor.ObservabilityMonitor;
import org.intermed.core.monitor.TpsPidController;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AlphaPerformanceSnapshotTest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @AfterEach
    void tearDown() {
        System.clearProperty("runtime.config.dir");
        System.clearProperty("observability.ewma.threshold.ms");
        RuntimeConfig.resetForTests();
        ObservabilityMonitor.resetForTests();
        MetricsRegistry.resetForTests();
        TpsPidController.get().resetForTests();
    }

    @Test
    void writesInitialAlphaPerformanceSnapshotWithoutNativeOverheadClaim() throws Exception {
        Path outputDir = outputDir();
        Files.createDirectories(outputDir);

        MemoryUsage heapBefore = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeapBefore = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        long gcCountBefore = gcCollectionCount();
        long gcMillisBefore = gcCollectionTimeMillis();

        System.setProperty("runtime.config.dir", outputDir.toString());
        System.setProperty("observability.ewma.threshold.ms", "1");
        RuntimeConfig.resetForTests();
        ObservabilityMonitor.resetForTests();
        TpsPidController.get().resetForTests();

        long tickStart = System.nanoTime();
        ObservabilityMonitor.onTickStart();
        Thread.sleep(35L);
        ObservabilityMonitor.onTickEnd("alpha_performance_snapshot");
        long tickMillis = Math.max(0L, (System.nanoTime() - tickStart) / 1_000_000L);

        Path jfrDump = Files.list(outputDir)
            .filter(path -> path.getFileName().toString().startsWith("intermed-tps-dump-"))
            .filter(path -> path.getFileName().toString().endsWith(".jfr"))
            .max(Comparator.comparing(path -> path.toFile().lastModified()))
            .orElseThrow(() -> new AssertionError("Expected synthetic JFR dump under " + outputDir));
        assertTrue(Files.size(jfrDump) > 0L);
        Path stableJfrDump = outputDir.resolve("alpha-performance-smoke.jfr");
        Files.copy(jfrDump, stableJfrDump, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("schema", "intermed-alpha-performance-snapshot-v1");
        snapshot.addProperty("generatedAt", Instant.now().toString());
        JsonObject nativeBaseline = readNativeBaseline(outputDir);
        if (nativeBaseline != null && nativeBaseline.has("environment")) {
            snapshot.add("environment", nativeBaseline.getAsJsonObject("environment").deepCopy());
        }
        snapshot.add("claimGuardrail", claimGuardrail(nativeBaseline));
        snapshot.add("lanes", nativeBaseline != null && nativeBaseline.has("lanes")
            ? nativeBaseline.getAsJsonArray("lanes").deepCopy()
            : lanes(
            heapBefore,
            nonHeapBefore,
            gcCollectionCount() - gcCountBefore,
            gcCollectionTimeMillis() - gcMillisBefore,
            tickMillis,
            stableJfrDump
        ));
        snapshot.add("internalHotPathEvidence", internalHotPathEvidence());

        Path snapshotPath = outputDir.resolve("alpha-performance-snapshot.json");
        Files.writeString(snapshotPath, GSON.toJson(snapshot), StandardCharsets.UTF_8);
        assertTrue(Files.size(snapshotPath) > 0L);
    }

    private static JsonObject claimGuardrail(JsonObject nativeBaseline) {
        if (nativeBaseline != null
                && nativeBaseline.has("claimGuardrail")
                && nativeBaseline.get("claimGuardrail").isJsonObject()) {
            JsonObject guardrail = nativeBaseline.getAsJsonObject("claimGuardrail").deepCopy();
            guardrail.addProperty("finalOverheadTargetClaimed", false);
            guardrail.addProperty("notes",
                "Native baseline evidence is preserved from native-loader-baseline.json. This remains an initial alpha snapshot, not a 10-15% overhead claim.");
            return guardrail;
        }
        JsonObject guardrail = new JsonObject();
        guardrail.addProperty("title", "Initial alpha performance snapshot");
        guardrail.addProperty("finalOverheadTargetClaimed", false);
        guardrail.addProperty("nativeBaselineComplete", false);
        guardrail.addProperty("notes",
            "This snapshot records local synthetic/JVM evidence and the status of required native baseline lanes. It is not a 10-15% overhead claim.");
        return guardrail;
    }

    private static JsonObject readNativeBaseline(Path outputDir) throws Exception {
        Path nativeBaseline = outputDir.resolve("native-loader-baseline.json");
        if (!Files.isRegularFile(nativeBaseline)) {
            return null;
        }
        return JsonParser.parseString(Files.readString(nativeBaseline, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonArray lanes(MemoryUsage heapBefore,
                                   MemoryUsage nonHeapBefore,
                                   long gcCountDelta,
                                   long gcMillisDelta,
                                   long tickMillis,
                                   Path jfrDump) throws Exception {
        JsonArray lanes = new JsonArray();
        lanes.add(notRunNativeLane("native-fabric-clean",
            "Run one clean Fabric dedicated-server boot with -Xmx768m and JFR enabled; record startup, heap, metaspace, GC, tick smoke, and the JFR dump."));
        lanes.add(notRunNativeLane("native-forge-clean",
            "Run one clean Forge dedicated-server boot with -Xmx768m and JFR enabled; record startup, heap, metaspace, GC, tick smoke, and the JFR dump."));
        lanes.add(intermedSyntheticLane(heapBefore, nonHeapBefore, gcCountDelta, gcMillisDelta, tickMillis, jfrDump));
        return lanes;
    }

    private static JsonObject notRunNativeLane(String name, String recommendedProcedure) {
        JsonObject lane = new JsonObject();
        lane.addProperty("name", name);
        lane.addProperty("status", "not-run");
        lane.addProperty("reason", "Native-loader baseline was not executed in this local alpha snapshot.");
        lane.addProperty("recommendedProcedure", recommendedProcedure);
        lane.add("measures", measureStatuses("not-run"));
        return lane;
    }

    private static JsonObject intermedSyntheticLane(MemoryUsage heapBefore,
                                                    MemoryUsage nonHeapBefore,
                                                    long gcCountDelta,
                                                    long gcMillisDelta,
                                                    long tickMillis,
                                                    Path jfrDump) throws Exception {
        JsonObject lane = new JsonObject();
        lane.addProperty("name", "intermed-attached-synthetic");
        lane.addProperty("status", "synthetic-smoke");
        lane.addProperty("reason", "Lightweight in-process InterMed observability smoke; not a real Minecraft modpack baseline.");

        JsonObject measures = new JsonObject();
        measures.add("startupTime", startupMeasure());
        measures.add("heap", bytesMeasure("observed", heapBefore.getUsed(), heapBefore.getCommitted()));
        measures.add("metaspace", bytesMeasure("observed", metaspaceUsedBytes(), nonHeapBefore.getCommitted()));
        measures.add("gcPauses", gcMeasure(gcCountDelta, gcMillisDelta));
        measures.add("tickTime", millisMeasure("synthetic-smoke", tickMillis));
        measures.add("jfrDump", jfrMeasure(jfrDump));
        lane.add("measures", measures);
        return lane;
    }

    private static JsonObject startupMeasure() {
        Path startup = Path.of("build", "reports", "startup", "warm-cache-startup.txt");
        JsonObject measure = new JsonObject();
        measure.addProperty("status", Files.isRegularFile(startup) ? "internal-warm-cache-report" : "not-run");
        measure.addProperty("artifact", startup.toString());
        measure.addProperty("interpretation", "Warm-cache unit evidence only; not native-loader startup baseline.");
        return measure;
    }

    private static JsonObject measureStatuses(String status) {
        JsonObject measures = new JsonObject();
        for (String key : new String[] {"startupTime", "heap", "metaspace", "gcPauses", "tickTime", "jfrDump"}) {
            JsonObject value = new JsonObject();
            value.addProperty("status", status);
            measures.add(key, value);
        }
        return measures;
    }

    private static JsonObject bytesMeasure(String status, long usedBytes, long committedBytes) {
        JsonObject measure = new JsonObject();
        measure.addProperty("status", status);
        measure.addProperty("usedBytes", Math.max(0L, usedBytes));
        measure.addProperty("committedBytes", Math.max(0L, committedBytes));
        return measure;
    }

    private static JsonObject gcMeasure(long collectionCount, long collectionMillis) {
        JsonObject measure = new JsonObject();
        measure.addProperty("status", "observed");
        measure.addProperty("collectionCountDelta", Math.max(0L, collectionCount));
        measure.addProperty("collectionTimeMillisDelta", Math.max(0L, collectionMillis));
        return measure;
    }

    private static JsonObject millisMeasure(String status, long millis) {
        JsonObject measure = new JsonObject();
        measure.addProperty("status", status);
        measure.addProperty("millis", Math.max(0L, millis));
        return measure;
    }

    private static JsonObject jfrMeasure(Path jfrDump) throws Exception {
        JsonObject measure = new JsonObject();
        measure.addProperty("status", "synthetic-smoke");
        measure.addProperty("artifact", jfrDump.toString());
        measure.addProperty("bytes", Files.size(jfrDump));
        return measure;
    }

    private static JsonArray internalHotPathEvidence() {
        JsonArray evidence = new JsonArray();
        evidence.add(hotPathArtifact("registry", "build/reports/microbench/registry-hot-path.txt"));
        evidence.add(hotPathArtifact("remapper", "build/reports/microbench/remapper-hot-path.txt"));
        evidence.add(hotPathArtifact("event-bus", "build/reports/microbench/event-bus-hot-path.txt"));
        return evidence;
    }

    private static JsonObject hotPathArtifact(String name, String pathText) {
        Path path = Path.of(pathText);
        JsonObject artifact = new JsonObject();
        artifact.addProperty("name", name);
        artifact.addProperty("path", pathText);
        artifact.addProperty("status", Files.isRegularFile(path) ? "present" : "missing");
        artifact.addProperty("interpretation", "Internal hot-path evidence only; not real modpack overhead.");
        return artifact;
    }

    private static long metaspaceUsedBytes() {
        return ManagementFactory.getMemoryPoolMXBeans().stream()
            .filter(pool -> pool.getName().toLowerCase(java.util.Locale.ROOT).contains("metaspace"))
            .map(MemoryPoolMXBean::getUsage)
            .filter(java.util.Objects::nonNull)
            .mapToLong(MemoryUsage::getUsed)
            .sum();
    }

    private static long gcCollectionCount() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(AlphaPerformanceSnapshotTest::nonNegativeCount)
            .sum();
    }

    private static long gcCollectionTimeMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(AlphaPerformanceSnapshotTest::nonNegativeTime)
            .sum();
    }

    private static long nonNegativeCount(GarbageCollectorMXBean bean) {
        return Math.max(0L, bean.getCollectionCount());
    }

    private static long nonNegativeTime(GarbageCollectorMXBean bean) {
        return Math.max(0L, bean.getCollectionTime());
    }

    private static Path outputDir() {
        String configured = System.getProperty("intermed.performance.outputDir");
        if (configured == null || configured.isBlank()) {
            return Path.of("build", "reports", "performance");
        }
        return Path.of(configured);
    }
}
