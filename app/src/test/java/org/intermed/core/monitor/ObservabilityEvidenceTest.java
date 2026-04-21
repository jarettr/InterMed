package org.intermed.core.monitor;

import org.intermed.core.config.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservabilityEvidenceTest {

    @AfterEach
    void tearDown() {
        ObservabilityMonitor.resetForTests();
        MetricsRegistry.resetForTests();
        TpsPidController.get().resetForTests();
        RuntimeConfig.resetForTests();
        System.clearProperty("runtime.config.dir");
        System.clearProperty("observability.ewma.threshold.ms");
    }

    @Test
    void writesMetricsSnapshotsAndJfrDumpEvidence() throws Exception {
        MetricsRegistry.resetForTests();
        TpsPidController.get().resetForTests();
        ObservabilityMonitor.resetForTests();

        Path outputDir = resolveOutputDir();
        Files.createDirectories(outputDir);

        MetricsRegistry metrics = MetricsRegistry.get();
        metrics.counterRaw("intermed_test_counter{mod=\"observability\"}", 3);
        metrics.gaugeRaw("intermed_test_gauge{mod=\"observability\"}", 12.5d);
        metrics.histogramRaw("intermed_test_histogram{mod=\"observability\"}", 42.0d);

        String prometheusPage = PrometheusExporter.buildPage();
        assertTrue(prometheusPage.contains("intermed_test_counter_total"));
        assertTrue(prometheusPage.contains("intermed_test_gauge"));
        assertTrue(prometheusPage.contains("intermed_test_histogram_bucket"));

        Path otelPath = outputDir.resolve("intermed-metrics.json");
        try (OtelJsonExporter exporter = OtelJsonExporter.start(otelPath)) {
            exporter.export();
        }
        assertTrue(Files.exists(otelPath));
        String otelJson = Files.readString(otelPath);
        assertTrue(otelJson.contains("\"resourceMetrics\""));
        assertTrue(otelJson.contains("intermed_test_counter"));
        assertTrue(otelJson.contains("intermed_test_gauge"));

        Path configDir = Files.createTempDirectory("intermed-observability-config");
        System.setProperty("runtime.config.dir", configDir.toString());
        System.setProperty("observability.ewma.threshold.ms", "55");
        RuntimeConfig.resetForTests();

        ObservabilityMonitor.onTickStart();
        Thread.sleep(150L);
        ObservabilityMonitor.onTickEnd("slow_mod");

        Path jfrDump = Files.list(configDir)
            .filter(path -> path.getFileName().toString().startsWith("intermed-tps-dump-"))
            .filter(path -> path.getFileName().toString().endsWith(".jfr"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected JFR dump in " + configDir));

        long jfrBytes = Files.size(jfrDump);
        assertTrue(jfrBytes > 0L, "JFR dump should not be empty");
        assertTrue(ObservabilityMonitor.isModThrottled("slow_mod"));
        assertTrue(metrics.countersSnapshot().containsKey("intermed_jfr_dump_total{mod=\"slow_mod\"}"));
        assertFalse(prometheusPage.isBlank());
        assertFalse(otelJson.isBlank());

        Files.writeString(outputDir.resolve("observability-evidence.txt"), """
            observability_evidence:
              prometheus_page_bytes: %d
              otel_json_path: %s
              otel_json_bytes: %d
              jfr_dump_path: %s
              jfr_dump_bytes: %d
            """.formatted(
            prometheusPage.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
            otelPath.toAbsolutePath(),
            Files.size(otelPath),
            jfrDump.toAbsolutePath(),
            jfrBytes
        ));
    }

    @Test
    void pidBackpressureSheddingIsSeparateFromHardModThrottle() {
        ObservabilityMonitor.resetForTests();
        TpsPidController.get().resetForTests();

        assertFalse(ObservabilityMonitor.isModThrottled("healthy_mod"));

        TpsPidController.get().update(5_000.0);

        assertTrue(ObservabilityMonitor.shouldShedEvent(false));
        assertFalse(ObservabilityMonitor.shouldShedEvent(true));
        assertFalse(ObservabilityMonitor.isModThrottled("healthy_mod"));
        assertTrue(ObservabilityMonitor.shouldSkipModEvent("healthy_mod", false));
        assertFalse(ObservabilityMonitor.shouldSkipModEvent("healthy_mod", true));
    }

    private static Path resolveOutputDir() {
        String configured = System.getProperty("intermed.observability.outputDir");
        if (configured == null || configured.isBlank()) {
            return Path.of("build", "reports", "observability");
        }
        return Path.of(configured);
    }
}
