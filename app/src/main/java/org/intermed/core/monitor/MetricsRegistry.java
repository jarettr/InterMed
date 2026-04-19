package org.intermed.core.monitor;

import org.intermed.core.classloading.TransformationContext;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe in-process metric store: counters, gauges, and histograms.
 *
 * <p>Metric names may embed label pairs using the Prometheus convention:
 * {@code metric_name{key="value",key2="value2"}}. Labels are preserved
 * verbatim in the key and extracted by exporters.
 *
 * <p>Mod attribution is automatic: if a {@link TransformationContext} is
 * active on the calling thread the mod ID is appended as {@code mod="<id>"}
 * unless the caller already supplies a {@code mod} label.
 */
public final class MetricsRegistry {

    // ── singleton ─────────────────────────────────────────────────────────────

    private static final MetricsRegistry INSTANCE = new MetricsRegistry();

    public static MetricsRegistry get() { return INSTANCE; }

    private MetricsRegistry() {}

    // ── storage ───────────────────────────────────────────────────────────────

    private final ConcurrentHashMap<String, LongAdder>  counters   = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> gauges     = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, HistogramData> histograms = new ConcurrentHashMap<>();

    // ── counter ───────────────────────────────────────────────────────────────

    /** Increments a counter by 1, automatically appending the current mod label. */
    public void counter(String name) { counter(name, 1L); }

    /** Increments a counter by {@code delta}, automatically appending the current mod label. */
    public void counter(String name, long delta) {
        counters.computeIfAbsent(attributed(name), k -> new LongAdder()).add(delta);
    }

    /** Increments a counter with an explicit key string (labels already embedded). */
    public void counterRaw(String key, long delta) {
        counters.computeIfAbsent(key, k -> new LongAdder()).add(delta);
    }

    // ── gauge ─────────────────────────────────────────────────────────────────

    /** Sets a gauge to an absolute value (double stored as long bits). */
    public void gauge(String name, double value) {
        gauges.computeIfAbsent(attributed(name), k -> new AtomicLong())
              .set(Double.doubleToRawLongBits(value));
    }

    /** Sets a gauge with an explicit key string (labels already embedded). */
    public void gaugeRaw(String key, double value) {
        gauges.computeIfAbsent(key, k -> new AtomicLong())
              .set(Double.doubleToRawLongBits(value));
    }

    public double getGauge(String key) {
        AtomicLong cell = gauges.get(key);
        return cell == null ? 0.0 : Double.longBitsToDouble(cell.get());
    }

    // ── histogram ─────────────────────────────────────────────────────────────

    /**
     * Records an observation into a histogram, automatically appending the current mod label.
     * Default bucket boundaries: 1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000 ms.
     */
    public void histogram(String name, double value) {
        histograms.computeIfAbsent(attributed(name), HistogramData::defaultBuckets).observe(value);
    }

    /** Records an observation with custom bucket boundaries. */
    public void histogram(String name, double value, double[] buckets) {
        histograms.computeIfAbsent(attributed(name), k -> new HistogramData(buckets)).observe(value);
    }

    /** Records an observation with an explicit key string (labels already embedded). */
    public void histogramRaw(String key, double value) {
        histograms.computeIfAbsent(key, HistogramData::defaultBuckets).observe(value);
    }

    // ── snapshots ─────────────────────────────────────────────────────────────

    public Map<String, Long> countersSnapshot() {
        Map<String, Long> snap = new ConcurrentHashMap<>(counters.size());
        counters.forEach((k, v) -> snap.put(k, v.sum()));
        return Collections.unmodifiableMap(snap);
    }

    public Map<String, Double> gaugesSnapshot() {
        Map<String, Double> snap = new ConcurrentHashMap<>(gauges.size());
        gauges.forEach((k, v) -> snap.put(k, Double.longBitsToDouble(v.get())));
        return Collections.unmodifiableMap(snap);
    }

    public Map<String, HistogramData> histogramsSnapshot() {
        return Collections.unmodifiableMap(new ConcurrentHashMap<>(histograms));
    }

    // ── test reset ────────────────────────────────────────────────────────────

    public static void resetForTests() {
        MetricsRegistry r = INSTANCE;
        r.counters.clear();
        r.gauges.clear();
        r.histograms.clear();
    }

    // ── mod attribution helper ─────────────────────────────────────────────────

    /**
     * Appends {@code mod="<id>"} to the metric key if a TransformationContext
     * is active on the calling thread and the key does not already contain a
     * {@code mod=} label.
     */
    static String attributed(String name) {
        if (name == null) return "unknown";
        String modId = TransformationContext.currentModIdOr(null);
        if (modId == null || name.contains("mod=")) {
            return name;
        }
        // Insert or append the label block
        int brace = name.indexOf('{');
        if (brace < 0) {
            return name + "{mod=\"" + modId + "\"}";
        }
        // Already has label block — append before the closing brace
        int close = name.lastIndexOf('}');
        if (close < 0) return name + ",mod=\"" + modId + "\"}";
        return name.substring(0, close) + ",mod=\"" + modId + "\"" + name.substring(close);
    }

    // ── HistogramData ─────────────────────────────────────────────────────────

    /**
     * Bucketed histogram: records count and sum, plus per-bucket cumulative counts.
     * Bucket boundaries are upper-inclusive (le= semantics, Prometheus-compatible).
     */
    public static final class HistogramData {

        private static final double[] DEFAULT_BUCKETS =
            {1, 5, 10, 25, 50, 100, 250, 500, 1000, 2500, 5000};

        public final double[] upperBounds;

        private final LongAdder[] bucketCounts;
        private final LongAdder totalCount = new LongAdder();
        private final LongAdder totalSumBits = new LongAdder(); // bits of double sum

        // We track the double sum via a separate volatile to avoid long-adder precision loss
        // for the sum itself; the LongAdder above is unused for sum — using a simple
        // compare-and-set loop on AtomicLong instead.
        private final AtomicLong sumBitsCell = new AtomicLong(Double.doubleToRawLongBits(0.0));

        HistogramData(double[] upperBounds) {
            this.upperBounds   = Arrays.copyOf(upperBounds, upperBounds.length);
            this.bucketCounts  = new LongAdder[upperBounds.length];
            for (int i = 0; i < bucketCounts.length; i++) {
                bucketCounts[i] = new LongAdder();
            }
        }

        static HistogramData defaultBuckets(String ignored) {
            return new HistogramData(DEFAULT_BUCKETS);
        }

        public void observe(double value) {
            for (int i = 0; i < upperBounds.length; i++) {
                if (value <= upperBounds[i]) {
                    bucketCounts[i].increment();
                }
            }
            totalCount.increment();
            // CAS loop to accumulate the double sum
            long prev, next;
            do {
                prev = sumBitsCell.get();
                next = Double.doubleToRawLongBits(Double.longBitsToDouble(prev) + value);
            } while (!sumBitsCell.compareAndSet(prev, next));
        }

        /** Cumulative counts per bucket (index matches {@link #upperBounds}). */
        public long[] bucketCounts() {
            long[] result = new long[bucketCounts.length];
            for (int i = 0; i < bucketCounts.length; i++) {
                result[i] = bucketCounts[i].sum();
            }
            return result;
        }

        public long count() { return totalCount.sum(); }

        public double sum() { return Double.longBitsToDouble(sumBitsCell.get()); }
    }
}
