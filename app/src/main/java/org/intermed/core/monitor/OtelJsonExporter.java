package org.intermed.core.monitor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.core.InterMedVersion;
import org.intermed.core.classloading.TcclInterceptor;

import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Periodically serialises {@link MetricsRegistry} snapshots to a file in
 * <a href="https://opentelemetry.io/docs/specs/otel/protocol/file-exporter/">
 * OTLP JSON</a> format (ResourceMetrics envelope).
 *
 * <p>The file is overwritten on every export tick (not appended) so the last
 * snapshot is always available as a plain JSON file.  An external collector
 * (e.g. the OTel Collector's filelog receiver) can tail the file or read it
 * on-demand.
 *
 * <h3>Schema</h3>
 * <pre>
 * {
 *   "resourceMetrics": [{
 *     "resource": { "attributes": [{ "key": "service.name", "value": { "stringValue": "intermed" } }] },
 *     "scopeMetrics": [{
 *       "scope": { "name": "intermed", "version": "8.0-SNAPSHOT" },
 *       "metrics": [
 *         { "name": "...", "sum": { ... } },          // counters
 *         { "name": "...", "gauge": { ... } },         // gauges
 *         { "name": "...", "histogram": { ... } }      // histograms
 *       ]
 *     }]
 *   }]
 * }
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * OtelJsonExporter exp = OtelJsonExporter.start(Paths.get("metrics.json"));
 * // …
 * exp.close(); // final flush + stop scheduler
 * }</pre>
 */
public final class OtelJsonExporter implements Closeable {

    private static final long EXPORT_INTERVAL_SECONDS = 30L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path outputPath;
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> task;

    private OtelJsonExporter(Path outputPath, ScheduledExecutorService scheduler) {
        this.outputPath = outputPath;
        this.scheduler  = scheduler;
    }

    /**
     * Starts a periodic exporter that writes to {@code outputPath} every
     * {@value EXPORT_INTERVAL_SECONDS} seconds.  The first export fires
     * immediately.
     *
     * @param outputPath file to write; parent directories must exist
     * @return the running exporter; call {@link #close()} to stop it
     */
    public static OtelJsonExporter start(Path outputPath) {
        ScheduledExecutorService scheduler = TcclInterceptor.wrap(Executors.newSingleThreadScheduledExecutor(
            TcclInterceptor.contextAwareFactory(r -> {
            Thread t = new Thread(r, "intermed-otel-exporter");
            t.setDaemon(true);
            return t;
        })));
        OtelJsonExporter exp = new OtelJsonExporter(outputPath, scheduler);
        exp.task = scheduler.scheduleAtFixedRate(exp::exportQuietly,
            0, EXPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        System.out.printf("[Metrics] OTLP JSON exporter started → %s (every %ds)%n",
            outputPath, EXPORT_INTERVAL_SECONDS);
        return exp;
    }

    /** Forces an immediate export outside the regular schedule. */
    public void export() throws IOException {
        writeSnapshot(buildPayload());
    }

    @Override
    public void close() {
        if (task != null) task.cancel(false);
        scheduler.shutdownNow();
        exportQuietly(); // final flush
        System.out.println("[Metrics] OTLP JSON exporter stopped.");
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void exportQuietly() {
        try {
            writeSnapshot(buildPayload());
        } catch (Exception e) {
            System.err.printf("[Metrics] OTLP export failed: %s%n", e.getMessage());
        }
    }

    private void writeSnapshot(JsonObject payload) throws IOException {
        byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        Files.write(outputPath, bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE);
    }

    // ── payload builder ───────────────────────────────────────────────────────

    static JsonObject buildPayload() {
        MetricsRegistry reg = MetricsRegistry.get();
        long nowNanos = System.currentTimeMillis() * 1_000_000L;

        JsonArray metricsArray = new JsonArray();

        // ── counters → Sum (monotonic) ─────────────────────────────────────
        for (Map.Entry<String, Long> e : reg.countersSnapshot().entrySet()) {
            JsonObject metric = new JsonObject();
            metric.addProperty("name", e.getKey());
            metric.addProperty("description", "InterMed counter");
            metric.addProperty("unit", "1");

            JsonObject dp = new JsonObject();
            dp.add("attributes", labelsToAttributes(PrometheusExporter.labelsOnly(e.getKey())));
            dp.addProperty("startTimeUnixNano", String.valueOf(0L));
            dp.addProperty("timeUnixNano", String.valueOf(nowNanos));
            dp.addProperty("asInt", String.valueOf(e.getValue()));

            JsonArray dataPoints = new JsonArray();
            dataPoints.add(dp);

            JsonObject sum = new JsonObject();
            sum.add("dataPoints", dataPoints);
            sum.addProperty("aggregationTemporality", 2); // CUMULATIVE
            sum.addProperty("isMonotonic", true);

            metric.add("sum", sum);
            metricsArray.add(metric);
        }

        // ── gauges → Gauge ─────────────────────────────────────────────────
        for (Map.Entry<String, Double> e : reg.gaugesSnapshot().entrySet()) {
            JsonObject metric = new JsonObject();
            metric.addProperty("name", e.getKey());
            metric.addProperty("description", "InterMed gauge");
            metric.addProperty("unit", "1");

            JsonObject dp = new JsonObject();
            dp.add("attributes", labelsToAttributes(PrometheusExporter.labelsOnly(e.getKey())));
            dp.addProperty("timeUnixNano", String.valueOf(nowNanos));
            dp.addProperty("asDouble", e.getValue());

            JsonArray dataPoints = new JsonArray();
            dataPoints.add(dp);

            JsonObject gauge = new JsonObject();
            gauge.add("dataPoints", dataPoints);

            metric.add("gauge", gauge);
            metricsArray.add(metric);
        }

        // ── histograms → Histogram ─────────────────────────────────────────
        for (Map.Entry<String, MetricsRegistry.HistogramData> e : reg.histogramsSnapshot().entrySet()) {
            MetricsRegistry.HistogramData h = e.getValue();
            JsonObject metric = new JsonObject();
            metric.addProperty("name", e.getKey());
            metric.addProperty("description", "InterMed histogram");
            metric.addProperty("unit", "ms");

            JsonObject dp = new JsonObject();
            dp.add("attributes", labelsToAttributes(PrometheusExporter.labelsOnly(e.getKey())));
            dp.addProperty("startTimeUnixNano", String.valueOf(0L));
            dp.addProperty("timeUnixNano", String.valueOf(nowNanos));
            dp.addProperty("count", String.valueOf(h.count()));
            dp.addProperty("sum", h.sum());

            // Explicit bounds + bucket counts
            JsonArray bounds = new JsonArray();
            for (double b : h.upperBounds) bounds.add(b);
            dp.add("explicitBounds", bounds);

            JsonArray bucketCounts = new JsonArray();
            for (long c : h.bucketCounts()) bucketCounts.add(String.valueOf(c));
            // +Inf bucket: total count minus the last upper bucket
            bucketCounts.add(String.valueOf(h.count()));
            dp.add("bucketCounts", bucketCounts);

            JsonArray dataPoints = new JsonArray();
            dataPoints.add(dp);

            JsonObject histogram = new JsonObject();
            histogram.add("dataPoints", dataPoints);
            histogram.addProperty("aggregationTemporality", 2); // CUMULATIVE

            metric.add("histogram", histogram);
            metricsArray.add(metric);
        }

        // ── scope ──────────────────────────────────────────────────────────
        JsonObject scope = new JsonObject();
        scope.addProperty("name", "intermed");
        scope.addProperty("version", InterMedVersion.BUILD_VERSION);

        JsonObject scopeMetrics = new JsonObject();
        scopeMetrics.add("scope", scope);
        scopeMetrics.add("metrics", metricsArray);

        JsonArray scopeMetricsArray = new JsonArray();
        scopeMetricsArray.add(scopeMetrics);

        // ── resource ───────────────────────────────────────────────────────
        JsonObject serviceNameValue = new JsonObject();
        serviceNameValue.addProperty("stringValue", "intermed");

        JsonObject serviceNameAttr = new JsonObject();
        serviceNameAttr.addProperty("key", "service.name");
        serviceNameAttr.add("value", serviceNameValue);

        JsonArray resourceAttrs = new JsonArray();
        resourceAttrs.add(serviceNameAttr);

        JsonObject resource = new JsonObject();
        resource.add("attributes", resourceAttrs);

        JsonObject resourceMetrics = new JsonObject();
        resourceMetrics.add("resource", resource);
        resourceMetrics.add("scopeMetrics", scopeMetricsArray);

        JsonArray resourceMetricsArray = new JsonArray();
        resourceMetricsArray.add(resourceMetrics);

        JsonObject root = new JsonObject();
        root.add("resourceMetrics", resourceMetricsArray);
        return root;
    }

    /**
     * Parses a Prometheus label block like {@code {mod="foo",le="1"}} into an
     * OTLP attributes array.  Returns an empty array for an empty block.
     */
    private static JsonArray labelsToAttributes(String labelBlock) {
        JsonArray attrs = new JsonArray();
        if (labelBlock == null || labelBlock.isBlank() || labelBlock.equals("{}")) {
            return attrs;
        }
        // Strip outer braces
        String inner = labelBlock.trim();
        if (inner.startsWith("{")) inner = inner.substring(1);
        if (inner.endsWith("}"))   inner = inner.substring(0, inner.length() - 1);

        // Split on comma, but only outside quotes
        int start = 0;
        boolean inQuote = false;
        for (int i = 0; i <= inner.length(); i++) {
            char c = i < inner.length() ? inner.charAt(i) : ',';
            if (c == '"') { inQuote = !inQuote; continue; }
            if (c == ',' && !inQuote) {
                String pair = inner.substring(start, i).trim();
                if (!pair.isEmpty()) attrs.add(parsePair(pair));
                start = i + 1;
            }
        }
        return attrs;
    }

    private static JsonObject parsePair(String pair) {
        int eq = pair.indexOf('=');
        String key = eq < 0 ? pair : pair.substring(0, eq).trim();
        String val = eq < 0 ? "" : pair.substring(eq + 1).trim();
        // Strip surrounding quotes from value
        if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
            val = val.substring(1, val.length() - 1);
        }
        JsonObject value = new JsonObject();
        value.addProperty("stringValue", val);
        JsonObject attr = new JsonObject();
        attr.addProperty("key", key);
        attr.add("value", value);
        return attr;
    }
}
