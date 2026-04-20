package org.intermed.core.monitor;

import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import org.intermed.core.classloading.TransformationContext;
import org.intermed.core.config.RuntimeConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive performance monitor: EWMA + CUSUM anomaly detection, automatic JFR
 * dump on confirmed TPS collapse, and per-mod throttling (ТЗ 3.2.6, Req 11).
 *
 * <h3>Algorithms</h3>
 * <ul>
 *   <li><b>EWMA</b> — exponential weighted moving average of tick duration;
 *       fast to react but smooths short spikes.</li>
 *   <li><b>CUSUM</b> — cumulative-sum control chart; detects sustained drifts
 *       that EWMA hides.  Two one-sided statistics track upward ({@code S_high})
 *       and downward ({@code S_low}) deviations from the target tick time.
 *       Parameters: allowance {@value #CUSUM_K} ms (half the step we wish to
 *       detect), decision interval {@value #CUSUM_H} ms.</li>
 * </ul>
 *
 * <h3>JFR dump</h3>
 * A continuous {@link Recording} is started the first time {@link #onTickStart()}
 * is called.  When CUSUM fires, the recording is dumped to
 * {@code <configDir>/intermed-tps-dump-<timestamp>.jfr} and the CUSUM accumulators
 * are reset.  Dumps are rate-limited to one per {@value #DUMP_COOLDOWN_MS} ms.
 *
 * <h3>Per-mod throttling</h3>
 * When a mod is identified as the culprit ({@code triggerAnomalyDetection} is
 * called with a non-null modId), it is throttled for {@value #THROTTLE_DURATION_MS}
 * ms.  Callers can check {@link #isModThrottled(String)} before dispatching events
 * to that mod to skip it during the penalty window.  After the window expires the
 * mod resumes normally; accumulated tick time is not reset.
 */
public class ObservabilityMonitor {

    // ── EWMA ──────────────────────────────────────────────────────────────────
    private static final double EWMA_ALPHA = 0.2;
    // volatile: written by the server tick thread, read by Prometheus/OTEL/CLI threads.
    private static volatile double ewmaTickTime = 50.0;

    // ── CUSUM ─────────────────────────────────────────────────────────────────
    /** Target / baseline tick duration in ms (≈ 20 TPS). */
    private static final double CUSUM_TARGET_MS = 50.0;
    /** Allowance — half the step size we want to detect (ms). */
    private static final double CUSUM_K = 5.0;
    /** Decision interval — threshold at which we raise an alert (ms). */
    private static final double CUSUM_H = 80.0;
    // volatile: written by tick thread, read by exporter threads.
    private static volatile double cusumHigh = 0.0;
    private static volatile double cusumLow  = 0.0;

    // ── Timing ────────────────────────────────────────────────────────────────
    // volatile: written by onTickStart, read by onTickEnd (both on tick thread, but
    // also read by isModThrottled callers on other threads via indirect modEwma access).
    private static volatile long lastTickStart = 0;

    // ── JFR dump ──────────────────────────────────────────────────────────────
    private static final long DUMP_COOLDOWN_MS = 30_000L; // min 30 s between dumps
    private static volatile Recording jfrRecording;
    private static final AtomicLong lastDumpTime = new AtomicLong(0);

    // ── Per-mod throttle ──────────────────────────────────────────────────────
    private static final long THROTTLE_DURATION_MS = 5_000L;
    private static final ConcurrentHashMap<String, Long> throttleUntil = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Double> modEwma = new ConcurrentHashMap<>();

    // ── MetricsRegistry ───────────────────────────────────────────────────────
    private static final MetricsRegistry METRICS = MetricsRegistry.get();

    // =========================================================================

    public static void onTickStart() {
        lastTickStart = System.currentTimeMillis();
        ensureJfrRecordingStarted();
    }

    /**
     * Called at the end of a tick.  Mod attribution is resolved from
     * {@link TransformationContext}; {@code currentModContext} is used as
     * a fallback when no context is active (e.g. bridge code).
     */
    public static void onTickEnd(String currentModContext) {
        if (lastTickStart == 0) return;
        long tickDelta = System.currentTimeMillis() - lastTickStart;

        String modId = TransformationContext.currentModIdOr(
            currentModContext != null ? currentModContext : "unknown");

        // ── EWMA update ───────────────────────────────────────────────────────
        ewmaTickTime = EWMA_ALPHA * tickDelta + (1.0 - EWMA_ALPHA) * ewmaTickTime;

        // ── Per-mod EWMA (for throttle decisions) ─────────────────────────────
        modEwma.merge(modId, (double) tickDelta,
            (prev, cur) -> EWMA_ALPHA * cur + (1.0 - EWMA_ALPHA) * prev);

        // ── CUSUM update ──────────────────────────────────────────────────────
        double deviation = tickDelta - CUSUM_TARGET_MS;
        cusumHigh = Math.max(0.0, cusumHigh + deviation - CUSUM_K);
        cusumLow  = Math.max(0.0, cusumLow  - deviation - CUSUM_K);

        // ── Metrics export ────────────────────────────────────────────────────
        METRICS.gaugeRaw("intermed_tick_ewma_ms{mod=\"" + modId + "\"}", ewmaTickTime);
        METRICS.gaugeRaw("intermed_cusum_high", cusumHigh);
        METRICS.gaugeRaw("intermed_cusum_low",  cusumLow);

        // ── PID backpressure update (ТЗ 3.5.5) ───────────────────────────────
        TpsPidController.get().update(ewmaTickTime);

        // ── EWMA alert ────────────────────────────────────────────────────────
        if (ewmaTickTime > RuntimeConfig.get().getObservabilityEwmaThresholdMs()) {
            triggerAnomalyDetection(ewmaTickTime, modId, "EWMA");
        }

        // ── CUSUM alert ───────────────────────────────────────────────────────
        if (cusumHigh > CUSUM_H) {
            triggerCusumAlert("upward", modId);
            cusumHigh = 0.0; // reset after alert
        } else if (cusumLow > CUSUM_H) {
            // Sustained recovery faster than baseline — unexpected; log but don't throttle
            System.out.printf("[CUSUM] Downward drift detected — server recovered faster than expected (S_low=%.1f)%n", cusumLow);
            cusumLow = 0.0;
        }
    }

    // ── Public metrics API ────────────────────────────────────────────────────

    /** Returns the current EWMA-smoothed tick duration in milliseconds (target: 50 ms ≈ 20 TPS). */
    public static double getEwmaTickTime() { return ewmaTickTime; }

    // ── Public throttle API ───────────────────────────────────────────────────

    /**
     * Returns {@code true} if the mod is currently in its penalty window and
     * its event callbacks should be skipped this tick.
     *
     * <p>Also applies PID-based probabilistic backpressure shedding (ТЗ 3.5.5):
     * even outside the hard throttle window, non-critical events are shed with
     * probability equal to the current backpressure level.
     */
    public static boolean isModThrottled(String modId) {
        if (modId == null) return false;
        Long until = throttleUntil.get(modId);
        if (until != null && System.currentTimeMillis() < until) return true;
        // PID backpressure shedding for non-critical events
        return TpsPidController.shouldSkipEvent(/* eventCritical */ false);
    }

    /** Clears throttle state for all mods (e.g. after a server reload). */
    public static void clearThrottles() {
        throttleUntil.clear();
    }

    public static void resetForTests() {
        ewmaTickTime = 50.0;
        cusumHigh    = 0.0;
        cusumLow     = 0.0;
        lastTickStart = 0;
        lastDumpTime.set(0);
        throttleUntil.clear();
        modEwma.clear();
        stopJfrRecording();
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static void triggerAnomalyDetection(double avgTickTime, String suspectModId, String source) {
        double tps = 1000.0 / avgTickTime;
        System.out.printf("\033[1;31m[JFR Monitor] TPS DROP (%s)! Current: %.2f TPS, suspect: %s\033[0m%n",
            source, tps, suspectModId);

        METRICS.counterRaw("intermed_tps_drop_total{mod=\"" + suspectModId + "\",source=\"" + source + "\"}", 1L);
        METRICS.gaugeRaw("intermed_tps{mod=\"" + suspectModId + "\"}", tps);

        try {
            HeavyModTickEvent event = new HeavyModTickEvent();
            event.modId        = suspectModId;
            event.durationMs   = avgTickTime;
            event.ewmaTickTime = ewmaTickTime;
            event.commit();
        } catch (Throwable ignored) {}

        // Throttle the suspected mod
        applyThrottle(suspectModId);
    }

    private static void triggerCusumAlert(String direction, String suspectModId) {
        System.out.printf(
            "\033[1;31m[CUSUM] Sustained %s TPS drift! S_high=%.1f S_low=%.1f suspect=%s\033[0m%n",
            direction, cusumHigh, cusumLow, suspectModId);

        METRICS.counterRaw("intermed_cusum_alert_total{direction=\"" + direction
            + "\",mod=\"" + suspectModId + "\"}", 1L);

        triggerAnomalyDetection(ewmaTickTime, suspectModId, "CUSUM");
        dumpJfrIfCooledDown(suspectModId);
    }

    private static void applyThrottle(String modId) {
        if (modId == null || modId.equals("unknown") || modId.equals("intermed_core")) return;
        long now = System.currentTimeMillis();
        throttleUntil.put(modId, now + THROTTLE_DURATION_MS);
        System.out.printf("[Throttle] Mod '%s' throttled for %d ms%n", modId, THROTTLE_DURATION_MS);
        METRICS.counterRaw("intermed_mod_throttle_total{mod=\"" + modId + "\"}", 1L);
    }

    // ── JFR recording helpers ─────────────────────────────────────────────────

    private static void ensureJfrRecordingStarted() {
        if (jfrRecording != null) return;
        try {
            if (!FlightRecorder.isAvailable()) return;
            Recording rec = new Recording();
            rec.setName("intermed-continuous");
            rec.setDuration(null); // run indefinitely
            // Keep at most 60 minutes of data in a ring buffer to cap heap usage
            rec.setMaxAge(Duration.ofMinutes(60));
            rec.enable("jdk.ThreadCPULoad").withPeriod(Duration.ofSeconds(1));
            rec.enable("jdk.GCHeapSummary");
            rec.enable("org.intermed.ServerTick");
            rec.enable("org.intermed.HeavyModTick");
            rec.start();
            jfrRecording = rec;
        } catch (Throwable ignored) {
            // JFR not available in this JVM build — silently skip
        }
    }

    private static void dumpJfrIfCooledDown(String modId) {
        long now = System.currentTimeMillis();
        long last = lastDumpTime.get();
        if (now - last < DUMP_COOLDOWN_MS) return;
        if (!lastDumpTime.compareAndSet(last, now)) return; // lost the race

        Recording rec = jfrRecording;
        if (rec == null) return;

        try {
            Path dumpDir = RuntimeConfig.get().getConfigDir();
            String timestamp = String.valueOf(Instant.now().toEpochMilli());
            Path dumpFile = dumpDir.resolve("intermed-tps-dump-" + timestamp + ".jfr");
            rec.dump(dumpFile);
            System.out.printf("[JFR] TPS-drop dump written to %s (suspect mod: %s)%n",
                dumpFile, modId);
            METRICS.counterRaw("intermed_jfr_dump_total{mod=\"" + modId + "\"}", 1L);
        } catch (IOException e) {
            System.err.printf("[JFR] Failed to write dump: %s%n", e.getMessage());
        }
    }

    private static void stopJfrRecording() {
        Recording rec = jfrRecording;
        jfrRecording = null;
        if (rec != null) {
            try { rec.stop(); rec.close(); } catch (Throwable ignored) {}
        }
    }
}
