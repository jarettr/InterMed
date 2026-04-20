package org.intermed.core.sandbox;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Erlang/OTP-style supervisor node that wraps a single supervised child and
 * applies a configurable restart strategy (ТЗ 3.5.7).
 *
 * <h3>Restart strategies</h3>
 * <ul>
 *   <li>{@link RestartStrategy#ONE_FOR_ONE} — restart only the failed child; no
 *       other siblings are affected (default for independent sandbox workers).</li>
 *   <li>{@link RestartStrategy#ONE_FOR_ALL} — restart the entire supervised group;
 *       used when siblings share mutable state and partial failure would corrupt
 *       the group (e.g. a set of co-operating event handlers).</li>
 *   <li>{@link RestartStrategy#REST_FOR_ONE} — restart the failed child and all
 *       children started after it (used when later children depend on earlier ones
 *       but not vice-versa).</li>
 * </ul>
 *
 * <h3>Intensity and period</h3>
 * To prevent a restart storm, the node applies a <em>max restarts within a time
 * window</em> policy (Erlang's {@code MaxR / MaxT}).  When the restart count
 * exceeds {@code maxRestarts} within {@code periodMs}, the node escalates the
 * failure to its parent supervisor (or shuts itself down if it has no parent).
 *
 * <h3>Integration with {@link SupervisorTree}</h3>
 * Nodes are registered with a {@link SupervisorTree} which routes escalated
 * failures and coordinates ONE_FOR_ALL / REST_FOR_ONE sibling restarts.
 */
public final class SupervisorNode {

    private static final Logger LOG = Logger.getLogger(SupervisorNode.class.getName());

    // ── Restart strategy ──────────────────────────────────────────────────────

    public enum RestartStrategy {
        /** Restart only the failed child. */
        ONE_FOR_ONE,
        /** Restart all children in the supervision group. */
        ONE_FOR_ALL,
        /** Restart the failed child and all children started after it. */
        REST_FOR_ONE
    }

    // ── State machine ─────────────────────────────────────────────────────────

    public enum NodeState {
        RUNNING,
        RESTARTING,
        STOPPED,
        ESCALATED
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final String modId;
    private final RestartStrategy strategy;
    private final int maxRestarts;
    private final long periodMs;
    private final Supplier<SandboxedEntrypoint> childFactory;

    private final AtomicReference<NodeState> state = new AtomicReference<>(NodeState.STOPPED);
    private final AtomicReference<SandboxedEntrypoint> child = new AtomicReference<>();
    private final AtomicInteger restartCount = new AtomicInteger(0);
    private final AtomicLong periodStart    = new AtomicLong(0);
    private final AtomicLong totalRestarts  = new AtomicLong(0);
    private final AtomicLong lastFailureMs  = new AtomicLong(0);
    private volatile Throwable lastFailure  = null;

    /** Reference to the owning tree, used for escalation and sibling coordination. */
    private volatile SupervisorTree owner;

    /**
     * Creates a supervisor node.
     *
     * @param modId        the mod this node supervises (used for logging and metrics)
     * @param strategy     restart strategy
     * @param maxRestarts  max restart attempts within {@code periodMs} before escalation
     * @param periodMs     sliding window for restart intensity check (milliseconds)
     * @param childFactory factory that produces a fresh {@link SandboxedEntrypoint}
     */
    public SupervisorNode(String modId,
                          RestartStrategy strategy,
                          int maxRestarts,
                          long periodMs,
                          Supplier<SandboxedEntrypoint> childFactory) {
        this.modId        = modId;
        this.strategy     = strategy;
        this.maxRestarts  = maxRestarts;
        this.periodMs     = periodMs;
        this.childFactory = childFactory;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Starts the supervised child.  Safe to call multiple times: if the child is
     * already running, this is a no-op.
     */
    public void start() {
        if (!state.compareAndSet(NodeState.STOPPED, NodeState.RUNNING)) {
            return;
        }
        periodStart.set(System.currentTimeMillis());
        restartCount.set(0);
        SandboxedEntrypoint entrypoint = childFactory.get();
        child.set(entrypoint);
        LOG.fine(() -> "[Supervisor:" + modId + "] child started");
    }

    /**
     * Stops the supervised child and transitions to {@link NodeState#STOPPED}.
     * Any ongoing execution is interrupted via the entrypoint's own teardown.
     */
    public void stop() {
        state.set(NodeState.STOPPED);
        SandboxedEntrypoint ep = child.getAndSet(null);
        if (ep != null) {
            try {
                ep.teardown();
            } catch (Exception ignored) {}
        }
        LOG.fine(() -> "[Supervisor:" + modId + "] child stopped");
    }

    // ── Fault notification ────────────────────────────────────────────────────

    /**
     * Notifies this supervisor that its child has failed with {@code cause}.
     * The supervisor evaluates the restart intensity window and either:
     * <ul>
     *   <li>restarts the child (and optionally siblings via strategy), or</li>
     *   <li>escalates to the owning {@link SupervisorTree}.</li>
     * </ul>
     *
     * @param cause  the exception that caused the failure
     * @return {@code true} if the child was restarted, {@code false} if escalated
     */
    public boolean notifyFailure(Throwable cause) {
        lastFailure   = cause;
        lastFailureMs.set(System.currentTimeMillis());
        LOG.log(Level.WARNING, "[Supervisor:" + modId + "] child failed", cause);

        if (!state.compareAndSet(NodeState.RUNNING, NodeState.RESTARTING)) {
            // Already restarting or stopped — ignore
            return false;
        }

        // ── Intensity check ──────────────────────────────────────────────────
        long now = System.currentTimeMillis();
        long windowStart = periodStart.get();
        if (now - windowStart > periodMs) {
            // New period — reset counter
            periodStart.set(now);
            restartCount.set(0);
        }
        int attempts = restartCount.incrementAndGet();
        totalRestarts.incrementAndGet();

        if (attempts > maxRestarts) {
            LOG.warning("[Supervisor:" + modId + "] restart intensity exceeded ("
                + attempts + "/" + maxRestarts + " within " + periodMs + "ms) — escalating");
            state.set(NodeState.ESCALATED);
            if (owner != null) {
                owner.onNodeEscalated(this, cause);
            }
            return false;
        }

        // ── Restart ──────────────────────────────────────────────────────────
        try {
            SandboxedEntrypoint fresh = childFactory.get();
            SandboxedEntrypoint previous = child.getAndSet(fresh);
            if (previous != null) {
                try {
                    previous.teardown();
                } catch (Exception teardownError) {
                    LOG.log(Level.WARNING, "[Supervisor:" + modId + "] teardown after failure failed", teardownError);
                }
            }
            state.set(NodeState.RUNNING);
            LOG.info("[Supervisor:" + modId + "] child restarted (attempt "
                + attempts + "/" + maxRestarts + ")");

            // Notify tree for ONE_FOR_ALL / REST_FOR_ONE coordination
            if (owner != null && strategy != RestartStrategy.ONE_FOR_ONE) {
                owner.onRestartStrategy(this, strategy);
            }
            return true;
        } catch (Throwable restartFailed) {
            LOG.log(Level.SEVERE, "[Supervisor:" + modId + "] restart failed", restartFailed);
            state.set(NodeState.ESCALATED);
            if (owner != null) {
                owner.onNodeEscalated(this, restartFailed);
            }
            return false;
        }
    }

    // ── Supervised execution ──────────────────────────────────────────────────

    /**
     * Executes {@code task} under supervision.  If the task throws, this method
     * calls {@link #notifyFailure(Throwable)} and rethrows the exception wrapped
     * in a {@link SupervisedExecutionException}.
     *
     * @param task  work to execute within the sandbox
     * @param <V>   return type
     * @return result of {@code task}
     * @throws SupervisedExecutionException if the task fails
     */
    public <V> V execute(Callable<V> task) throws SupervisedExecutionException {
        try {
            return task.call();
        } catch (Throwable t) {
            notifyFailure(t);
            throw new SupervisedExecutionException(modId, t);
        }
    }

    /**
     * Executes a {@link Runnable} under supervision.
     */
    public void execute(Runnable task) throws SupervisedExecutionException {
        try {
            task.run();
        } catch (Throwable t) {
            notifyFailure(t);
            throw new SupervisedExecutionException(modId, t);
        }
    }

    // ── Accessors ─────────────────────────────────────────────────────────────

    public String modId()               { return modId; }
    public RestartStrategy strategy()   { return strategy; }
    public NodeState state()            { return state.get(); }
    public int restartCount()           { return restartCount.get(); }
    public long totalRestarts()         { return totalRestarts.get(); }
    public Throwable lastFailure()      { return lastFailure; }
    public long lastFailureMs()         { return lastFailureMs.get(); }
    public SandboxedEntrypoint child()  { return child.get(); }

    void setOwner(SupervisorTree tree)  { this.owner = tree; }

    // ── Exception type ────────────────────────────────────────────────────────

    /** Wraps the original exception thrown by a supervised task. */
    public static final class SupervisedExecutionException extends Exception {
        private final String supervisedModId;

        SupervisedExecutionException(String modId, Throwable cause) {
            super("Supervised execution failed for mod: " + modId, cause);
            this.supervisedModId = modId;
        }

        public String supervisedModId() { return supervisedModId; }
    }
}
