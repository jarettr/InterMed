package org.intermed.core.monitor;

import java.util.concurrent.atomic.AtomicReference;

/**
 * PID regulator for server TPS budget management (ТЗ 3.5.5).
 *
 * <h3>Problem</h3>
 * EWMA + CUSUM detect anomalies but react slowly to rapid TPS fluctuations.
 * A hard throttle applied after a 5-second penalty window is too coarse and
 * causes visible player-facing freezes at the boundary when the throttle
 * is lifted.
 *
 * <h3>Solution</h3>
 * A discrete PID controller computes a continuous <em>backpressure level</em>
 * {@code [0.0, 1.0]} from the error between the target tick time (50 ms) and
 * the measured EWMA.  The backpressure value is applied to
 * {@link ObservabilityMonitor} and forwarded to the
 * {@link org.intermed.core.monitor.LockFreeEvent} ring-buffer drain loop, which
 * uses it to decide how many non-critical background events to skip per tick.
 *
 * <h3>PID equations</h3>
 * <pre>
 *   e(t)   = setpoint - measured   (positive when tick is slower than target)
 *   P      = Kp × e(t)
 *   I(t)   = I(t-1) + Ki × e(t) × Δt   (clamped to ±MAX_INTEGRAL)
 *   D      = Kd × (e(t) - e(t-1)) / Δt
 *   output = -(P + I + D)               (negative output → need to throttle)
 *   bp     = clamp(output / MAX_OUTPUT, 0.0, 1.0)
 * </pre>
 *
 * <p>Default gains are tunable via JVM system properties (see constants below).
 * The integral wind-up is prevented by clamping the accumulator.
 *
 * <h3>Integration</h3>
 * {@link ObservabilityMonitor#onTickEnd} calls {@link #update(double)} each tick.
 * The result is stored in {@link #currentBackpressure()} and queried by the
 * event dispatch loops to determine how aggressively to shed non-critical events.
 */
public final class TpsPidController {

    /** Target tick time in milliseconds (20 TPS = 50 ms/tick). */
    private static final double SETPOINT_MS = 50.0;

    // ── Gains (JVM-property tunable) ──────────────────────────────────────────
    private static final double DEFAULT_KP = Double.parseDouble(
        System.getProperty("intermed.pid.kp", "0.04"));
    private static final double DEFAULT_KI = Double.parseDouble(
        System.getProperty("intermed.pid.ki", "0.008"));
    private static final double DEFAULT_KD = Double.parseDouble(
        System.getProperty("intermed.pid.kd", "0.002"));

    private static final double MAX_INTEGRAL  = 500.0;
    private static final double MAX_OUTPUT    = 100.0; // ms deviation that saturates bp=1.0
    private static final double MIN_BACKPRESSURE = 0.0;
    private static final double MAX_BACKPRESSURE = 1.0;

    // ── Singleton ─────────────────────────────────────────────────────────────
    private static final TpsPidController INSTANCE = new TpsPidController(
        DEFAULT_KP, DEFAULT_KI, DEFAULT_KD);

    /** Returns the process-wide PID controller instance. */
    public static TpsPidController get() { return INSTANCE; }

    // ── State (written exclusively on the server-tick thread) ────────────────
    // volatile: the tick thread is the sole writer; other threads (Prometheus
    // exporter, OTEL, test harnesses) may read these fields concurrently.
    // volatile is sufficient for the single-writer / multiple-reader pattern
    // and avoids adding synchronisation cost to the hot update() path.
    private final double kp;
    private final double ki;
    private final double kd;

    private volatile double integral   = 0.0;
    private volatile double prevError  = 0.0;
    private volatile long   prevTimeMs = 0;

    /** Thread that first called {@link #update}; all subsequent calls must originate from the same thread. */
    private volatile Thread ownerThread = null;

    /** Published atomically so reporter threads can read without locking. */
    private final AtomicReference<Double> backpressure = new AtomicReference<>(0.0);

    TpsPidController(double kp, double ki, double kd) {
        this.kp = kp;
        this.ki = ki;
        this.kd = kd;
    }

    // ── Core update ───────────────────────────────────────────────────────────

    /**
     * Computes the new backpressure level from the measured tick time.
     *
     * <p>Must be called exactly once per server tick, from the tick thread
     * (same thread as {@link ObservabilityMonitor#onTickEnd}).
     *
     * @param measuredTickMs  the duration of the last tick in milliseconds
     * @return new backpressure level in {@code [0.0, 1.0]}
     */
    public double update(double measuredTickMs) {
        Thread caller = Thread.currentThread();
        Thread owner  = ownerThread;
        if (owner == null) {
            ownerThread = caller;
        } else if (owner != caller) {
            throw new IllegalStateException(
                "TpsPidController.update() called from '" + caller.getName()
                + "' but was first used on '" + owner.getName()
                + "' — must be called exclusively from the server-tick thread");
        }
        long now = System.currentTimeMillis();
        double dt = prevTimeMs > 0 ? Math.max(1.0, (double)(now - prevTimeMs)) / 1000.0
                                   : 0.05; // 50 ms default for first tick
        prevTimeMs = now;

        double error = SETPOINT_MS - measuredTickMs; // positive = server ahead of target

        // ── P term ─────────────────────────────────────────────────────────
        double P = kp * error;

        // ── I term (with anti-windup clamp) ────────────────────────────────
        integral += ki * error * dt;
        integral  = Math.max(-MAX_INTEGRAL, Math.min(MAX_INTEGRAL, integral));
        double I  = integral;

        // ── D term ─────────────────────────────────────────────────────────
        double D  = kd * (error - prevError) / dt;
        prevError = error;

        // ── Output → backpressure ───────────────────────────────────────────
        // Negative output means the server is behind target (output < 0 when
        // measuredTickMs > SETPOINT_MS, i.e. error < 0).
        // Backpressure increases when the server is behind target.
        double raw = -(P + I + D);
        double bp  = Math.max(MIN_BACKPRESSURE,
                     Math.min(MAX_BACKPRESSURE, raw / MAX_OUTPUT));

        backpressure.set(bp);
        return bp;
    }

    // ── Hot-path accessor ─────────────────────────────────────────────────────

    /**
     * Returns the most recently computed backpressure level.
     *
     * <p>Range: {@code [0.0, 1.0]}.  {@code 0.0} means no backpressure (server
     * is on time or ahead); {@code 1.0} means maximum backpressure (server is
     * severely behind — skip as many non-critical events as possible).
     */
    public static double currentBackpressure() {
        return INSTANCE.backpressure.get();
    }

    /**
     * Applies a probabilistic skip based on the current backpressure level.
     *
     * <p>Call this before dispatching a non-critical background event.
     * Returns {@code true} if the event should be skipped this tick.
     *
     * @param eventCritical  if {@code true}, the event is never skipped regardless
     *                       of backpressure
     */
    public static boolean shouldSkipEvent(boolean eventCritical) {
        if (eventCritical) return false;
        double bp = currentBackpressure();
        if (bp <= 0.0) return false;
        if (bp >= 1.0) return true;
        // Probabilistic shedding: skip with probability proportional to backpressure
        return Math.random() < bp;
    }

    // ── Diagnostics / test support ────────────────────────────────────────────

    public double getKp() { return kp; }
    public double getKi() { return ki; }
    public double getKd() { return kd; }
    public double getIntegral() { return integral; }
    public double getPrevError() { return prevError; }

    public synchronized void resetForTests() {
        integral    = 0.0;
        prevError   = 0.0;
        prevTimeMs  = 0;
        ownerThread = null;
        backpressure.set(0.0);
    }
}
