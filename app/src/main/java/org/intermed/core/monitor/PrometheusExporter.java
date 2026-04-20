package org.intermed.core.monitor;

import com.sun.net.httpserver.HttpServer;
import org.intermed.core.classloading.TcclInterceptor;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;

/**
 * Exposes {@link MetricsRegistry} in the Prometheus text format on an HTTP
 * server.  Uses only the JDK-bundled {@link HttpServer} — no extra runtime
 * dependencies required.
 *
 * <h3>Endpoint</h3>
 * {@code GET /metrics} — returns {@code text/plain; version=0.0.4; charset=utf-8}
 *
 * <h3>Format</h3>
 * Each metric name (with labels stripped) gets a {@code # HELP} / {@code # TYPE}
 * header followed by one sample line per label combination.  Counters and
 * histogram {@code _count} / {@code _sum} lines carry the {@code _total} suffix
 * per Prometheus conventions.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * PrometheusExporter exp = PrometheusExporter.start(9090);
 * // …
 * exp.close(); // stops the HTTP server
 * }</pre>
 */
public final class PrometheusExporter implements Closeable {

    private static final String CONTENT_TYPE =
        "text/plain; version=0.0.4; charset=utf-8";

    private final HttpServer server;

    private PrometheusExporter(HttpServer server) {
        this.server = server;
    }

    /**
     * Starts a Prometheus HTTP server on {@code port}.
     *
     * @param port TCP port to listen on (e.g. {@code 9090})
     * @return the running exporter; call {@link #close()} to stop it
     * @throws IOException if the server cannot bind to the port
     */
    public static PrometheusExporter start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/metrics", exchange -> {
            byte[] body = buildPage().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", CONTENT_TYPE);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.setExecutor(Executors.newSingleThreadExecutor(TcclInterceptor.contextAwareFactory(r -> {
            Thread t = new Thread(r, "intermed-prometheus-http");
            t.setDaemon(true);
            return t;
        })));
        server.start();
        System.out.printf("[Metrics] Prometheus endpoint started on http://0.0.0.0:%d/metrics%n", port);
        return new PrometheusExporter(server);
    }

    @Override
    public void close() {
        server.stop(0);
        System.out.println("[Metrics] Prometheus HTTP server stopped.");
    }

    // ── page builder ──────────────────────────────────────────────────────────

    static String buildPage() {
        MetricsRegistry reg = MetricsRegistry.get();
        StringBuilder sb = new StringBuilder(4096);

        // ── counters ──────────────────────────────────────────────────────────
        Map<String, Long> counters = new TreeMap<>(reg.countersSnapshot());
        String lastBase = null;
        for (Map.Entry<String, Long> e : counters.entrySet()) {
            String base = baseName(e.getKey());
            if (!base.equals(lastBase)) {
                sb.append("# HELP ").append(base).append("_total InterMed counter\n");
                sb.append("# TYPE ").append(base).append("_total counter\n");
                lastBase = base;
            }
            sb.append(base).append("_total");
            appendLabels(sb, e.getKey());
            sb.append(' ').append(e.getValue()).append('\n');
        }

        // ── gauges ────────────────────────────────────────────────────────────
        Map<String, Double> gauges = new TreeMap<>(reg.gaugesSnapshot());
        lastBase = null;
        for (Map.Entry<String, Double> e : gauges.entrySet()) {
            String base = baseName(e.getKey());
            if (!base.equals(lastBase)) {
                sb.append("# HELP ").append(base).append(" InterMed gauge\n");
                sb.append("# TYPE ").append(base).append(" gauge\n");
                lastBase = base;
            }
            sb.append(base);
            appendLabels(sb, e.getKey());
            sb.append(' ').append(formatDouble(e.getValue())).append('\n');
        }

        // ── histograms ────────────────────────────────────────────────────────
        Map<String, MetricsRegistry.HistogramData> histos = new TreeMap<>(reg.histogramsSnapshot());
        for (Map.Entry<String, MetricsRegistry.HistogramData> e : histos.entrySet()) {
            String base = baseName(e.getKey());
            String labelSuffix = labelsOnly(e.getKey()); // "{mod=\"x\",...}" or ""
            MetricsRegistry.HistogramData h = e.getValue();
            sb.append("# HELP ").append(base).append(" InterMed histogram\n");
            sb.append("# TYPE ").append(base).append(" histogram\n");

            long[] counts = h.bucketCounts();
            for (int i = 0; i < h.upperBounds.length; i++) {
                sb.append(base).append("_bucket");
                appendLabelsWith(sb, labelSuffix, "le", formatDouble(h.upperBounds[i]));
                sb.append(' ').append(counts[i]).append('\n');
            }
            // +Inf bucket
            sb.append(base).append("_bucket");
            appendLabelsWith(sb, labelSuffix, "le", "+Inf");
            sb.append(' ').append(h.count()).append('\n');

            sb.append(base).append("_count");
            if (!labelSuffix.isEmpty()) sb.append(labelSuffix);
            sb.append(' ').append(h.count()).append('\n');

            sb.append(base).append("_sum");
            if (!labelSuffix.isEmpty()) sb.append(labelSuffix);
            sb.append(' ').append(formatDouble(h.sum())).append('\n');
        }

        return sb.toString();
    }

    // ── formatting helpers ────────────────────────────────────────────────────

    /**
     * Extracts the metric base name (the part before any {@code {}).
     * Also replaces dots with underscores for Prometheus compatibility.
     */
    static String baseName(String key) {
        int brace = key.indexOf('{');
        String raw = brace < 0 ? key : key.substring(0, brace);
        return raw.replace('.', '_');
    }

    /**
     * Appends the label block from {@code key} (e.g. {@code {mod="foo"}}) to
     * {@code sb}.  Does nothing if the key has no label block.
     */
    private static void appendLabels(StringBuilder sb, String key) {
        String labels = labelsOnly(key);
        if (!labels.isEmpty()) sb.append(labels);
    }

    /**
     * Returns just the label block from {@code key}, e.g. {@code {mod="foo"}} or {@code ""}.
     */
    static String labelsOnly(String key) {
        int open  = key.indexOf('{');
        int close = key.lastIndexOf('}');
        if (open < 0 || close < 0 || close <= open) return "";
        return key.substring(open, close + 1);
    }

    /**
     * Appends label block to {@code sb}, merging existing labels from
     * {@code existingBlock} with an extra {@code extraKey="extraVal"} pair.
     */
    private static void appendLabelsWith(StringBuilder sb, String existingBlock,
                                          String extraKey, String extraVal) {
        if (existingBlock.isEmpty()) {
            sb.append('{').append(extraKey).append("=\"").append(extraVal).append("\"}");
        } else {
            // Insert the extra pair before the closing brace
            int close = existingBlock.lastIndexOf('}');
            sb.append(existingBlock, 0, close)
              .append(',').append(extraKey).append("=\"").append(extraVal).append("\"}");
        }
    }

    private static String formatDouble(double v) {
        if (Double.isNaN(v))      return "NaN";
        if (Double.isInfinite(v)) return v > 0 ? "+Inf" : "-Inf";
        // Trim unnecessary .0 suffix for whole numbers
        if (v == Math.floor(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) {
            return Long.toString((long) v);
        }
        return Double.toString(v);
    }
}
