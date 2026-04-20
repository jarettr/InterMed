package org.intermed.core.monitor;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-tick performance monitor implementing both EWMA and CUSUM
 * anomaly detection algorithms (ТЗ 3.2.6, Requirement 11).
 *
 * <h3>EWMA (Exponential Weighted Moving Average)</h3>
 * Smooths instantaneous TPS noise to give a stable running average.
 * {@code tps = tps + α * (currentTps - tps)}, α = 0.05.
 *
 * <h3>CUSUM (Cumulative Sum Control Chart)</h3>
 * Detects sustained downward shifts in TPS that EWMA can hide
 * because it over-smooths short degradations.
 * Two one-sided statistics are maintained:
 * <ul>
 *   <li>{@code S_low} — accumulates deficit below (target - slack); fires when
 *       {@code S_low > threshold}, indicating persistent lag.</li>
 *   <li>{@code S_high} — accumulates surplus above (target + slack); fires when
 *       {@code S_high > threshold}, indicating abnormally fast ticks (unlikely
 *       but indicates a frozen-time bug or miscalibration).</li>
 * </ul>
 * After an alarm, the statistic resets to 0 so fresh lag is detected again.
 */
public class PerformanceMonitor {

    private static final AtomicLong lastTickTime = new AtomicLong(0);
    // volatile: written by the server tick thread, read by Prometheus/OTEL exporter threads.
    private static volatile double tps = 20.0;

    // ── EWMA ──────────────────────────────────────────────────────────────────
    /** Smoothing factor: higher = more reactive, lower = smoother. */
    private static final double EWMA_ALPHA = 0.05;

    // ── CUSUM ─────────────────────────────────────────────────────────────────
    /** Reference value: expected nominal TPS. */
    private static final double CUSUM_TARGET    = 20.0;
    /**
     * Slack / allowance (k in CUSUM literature).
     * Deviations smaller than k are not accumulated — this prevents
     * normal short-lived wobble from accumulating toward an alarm.
     */
    private static final double CUSUM_SLACK     = 1.0;
    /**
     * Decision threshold (h in CUSUM literature).
     * An alarm fires when the cumulative statistic exceeds this value.
     * At SLACK=1.0, a threshold of 5.0 roughly means "5 consecutive ticks
     * each 1 TPS below target" — a genuine sustained degradation.
     */
    private static final double CUSUM_THRESHOLD = 5.0;

    /** Cumulative sum for downward TPS shift (lag detection). volatile: cross-thread visibility. */
    private static volatile double cusumLow  = 0.0;
    /** Cumulative sum for upward TPS shift (frozen-clock / miscalibration). volatile: cross-thread visibility. */
    private static volatile double cusumHigh = 0.0;

    private static final MetricsRegistry METRICS = MetricsRegistry.get();

    public static void onServerTick() {
        long currentTime = System.nanoTime();
        long lastTime = lastTickTime.getAndSet(currentTime);

        if (lastTime == 0) {
            return; // First tick — no delta yet.
        }

        long diff = currentTime - lastTime;
        double currentTps = 1_000_000_000.0 / diff;

        // ── EWMA ──────────────────────────────────────────────────────────────
        tps = tps + EWMA_ALPHA * (currentTps - tps);

        // Live gauge for Prometheus / OTEL exporters
        METRICS.gaugeRaw("intermed_tps", tps);

        // ── CUSUM ─────────────────────────────────────────────────────────────
        // S_low  detects sustained drops  (TPS < TARGET − SLACK)
        // S_high detects sustained surges (TPS > TARGET + SLACK)
        cusumLow  = Math.max(0.0, cusumLow  + (CUSUM_TARGET - currentTps) - CUSUM_SLACK);
        cusumHigh = Math.max(0.0, cusumHigh + (currentTps - CUSUM_TARGET) - CUSUM_SLACK);

        METRICS.gaugeRaw("intermed_cusum_low",  cusumLow);
        METRICS.gaugeRaw("intermed_cusum_high", cusumHigh);

        if (cusumLow > CUSUM_THRESHOLD) {
            System.out.printf(
                "\033[1;31m[CUSUM] Sustained lag alarm! S_low=%.2f (threshold=%.1f). EWMA TPS=%.2f\033[0m%n",
                cusumLow, CUSUM_THRESHOLD, tps);
            cusumLow = 0.0; // Reset after alarm to re-arm detection.
            triggerJfrDump();
        }

        if (cusumHigh > CUSUM_THRESHOLD) {
            System.out.printf(
                "\033[1;33m[CUSUM] Abnormal speed alarm! S_high=%.2f — check clock/frozen-tick bug. EWMA TPS=%.2f\033[0m%n",
                cusumHigh, CUSUM_THRESHOLD, tps);
            cusumHigh = 0.0;
        }

        // ── EWMA lag fallback ─────────────────────────────────────────────────
        if (tps < 15.0) {
            System.out.printf(
                "\033[1;31m[EWMA] LAG DETECTED! Current TPS: %.2f\033[0m%n", tps);
        }

        // ── JFR tick event ────────────────────────────────────────────────────
        try {
            InterMedTickEvent jfrEvent = new InterMedTickEvent();
            jfrEvent.tickPhase   = "server";
            jfrEvent.activeModId = "intermed_core";
            jfrEvent.commit();
        } catch (Throwable ignored) {}
    }

    public static double getTps() { return tps; }

    /** Returns current CUSUM low-side statistic (lag accumulator). */
    public static double getCusumLow()  { return cusumLow;  }

    /** Returns current CUSUM high-side statistic (speed accumulator). */
    public static double getCusumHigh() { return cusumHigh; }

    /** Resets CUSUM statistics — useful after a configuration reload. */
    public static void resetCusum() {
        cusumLow  = 0.0;
        cusumHigh = 0.0;
    }

    private static void triggerJfrDump() {
        try {
            HeavyModTickEvent dump = new HeavyModTickEvent();
            dump.modId = "intermed_core";
            dump.durationMs = 1000.0 / Math.max(tps, 0.01);
            dump.ewmaTickTime = dump.durationMs;
            dump.commit();
        } catch (Throwable ignored) {}
    }
}
