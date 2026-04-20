package org.intermed.core.sandbox;

import org.intermed.core.classloading.LazyInterMedClassLoader;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hot reload orchestrator for sandboxed mods (ТЗ 3.5.7).
 *
 * <h3>Hot reload without JVM restart</h3>
 * When a sandboxed mod crashes or must be updated, this controller:
 * <ol>
 *   <li>Calls {@link SupervisorTree#hotReload(String)} to stop the old child
 *       and start a fresh one from the registered factory.</li>
 *   <li>If the factory was registered with a new {@link ClassLoader} snapshot,
 *       the new entrypoint runs under the updated class definitions — no JVM
 *       restart required.</li>
 *   <li>Emits diagnostic events that {@link org.intermed.core.monitor.ObservabilityMonitor}
 *       can report via its metrics telemetry path.</li>
 * </ol>
 *
 * <h3>Integration with {@link PolyglotSandboxManager}</h3>
 * {@link PolyglotSandboxManager#executeSandboxed} wraps every sandbox invocation
 * in a {@link SupervisorNode#execute(Runnable)} call.  When the sandbox throws,
 * the node applies its restart strategy.  {@link HotReloadController} provides
 * the factory wiring so that a fresh {@link SandboxedEntrypoint} is created with
 * the latest class bytecode.
 *
 * <h3>Reload registry</h3>
 * Factories are registered per modId via {@link #register}.  An existing
 * registration can be replaced at any time (e.g. when a new JAR is hot-swapped
 * onto disk) — the next restart will use the updated factory.
 */
public final class HotReloadController {

    private static final Logger LOG = Logger.getLogger(HotReloadController.class.getName());

    private static final HotReloadController INSTANCE = new HotReloadController();

    public static HotReloadController get() { return INSTANCE; }

    // ── State ─────────────────────────────────────────────────────────────────

    private final Map<String, Supplier<SandboxedEntrypoint>> factories = new ConcurrentHashMap<>();
    private final Map<String, ReloadRecord> history = new ConcurrentHashMap<>();
    private final AtomicLong totalReloads = new AtomicLong();

    private HotReloadController() {}

    // ── Factory registration ──────────────────────────────────────────────────

    /**
     * Registers (or replaces) the entrypoint factory for {@code modId}.
     *
     * @param modId    mod identifier
     * @param factory  produces a fresh {@link SandboxedEntrypoint} on each call;
     *                 may capture a {@link LazyInterMedClassLoader} for class isolation
     */
    public void register(String modId, Supplier<SandboxedEntrypoint> factory) {
        factories.put(modId, factory);
        LOG.fine(() -> "[HotReload] factory registered for " + modId);
    }

    /** Removes the factory for {@code modId}. */
    public void unregister(String modId) {
        factories.remove(modId);
    }

    /**
     * Returns the registered factory for {@code modId}, or {@code null}.
     */
    public Supplier<SandboxedEntrypoint> factoryFor(String modId) {
        return factories.get(modId);
    }

    // ── Hot reload ─────────────────────────────────────────────────────────────

    /**
     * Triggers a hot reload for {@code modId} in the given {@link SupervisorTree}.
     *
     * <p>If the factory was updated since the last registration (e.g. because a
     * new JAR was placed on disk and a new factory was registered via
     * {@link #register}), the newly started child will use the updated code.
     *
     * @param tree   the supervisor tree that owns the node
     * @param modId  the mod to reload
     * @return {@code true} if the reload was initiated, {@code false} if no node
     *         was found in the tree for this modId
     */
    public boolean triggerReload(SupervisorTree tree, String modId) {
        long startMs = System.currentTimeMillis();
        LOG.info("[HotReload] triggered for mod=" + modId);

        // Re-register node with latest factory if available — allows factory swap
        Supplier<SandboxedEntrypoint> factory = factories.get(modId);
        if (factory != null) {
            SupervisorNode existing = tree.nodeFor(modId);
            if (existing == null) {
                // Node not registered yet — create it on-demand
                SupervisorNode node = new SupervisorNode(
                    modId,
                    SupervisorNode.RestartStrategy.ONE_FOR_ONE,
                    /* maxRestarts */ 5,
                    /* periodMs   */ 60_000,
                    factory
                );
                tree.register(node);
                long elapsed = System.currentTimeMillis() - startMs;
                recordReload(modId, true, elapsed, null);
                totalReloads.incrementAndGet();
                LOG.info("[HotReload] new node started for mod=" + modId
                    + " in " + elapsed + "ms");
                return true;
            }
        }

        boolean started = tree.hotReload(modId);
        long elapsed = System.currentTimeMillis() - startMs;
        recordReload(modId, started, elapsed, null);
        if (started) totalReloads.incrementAndGet();
        LOG.log(started ? Level.INFO : Level.WARNING,
            "[HotReload] " + (started ? "completed" : "failed") + " for mod="
            + modId + " in " + elapsed + "ms");
        return started;
    }

    /**
     * Convenience wrapper that uses the {@link SupervisorTree#root()} tree.
     */
    public boolean triggerReload(String modId) {
        return triggerReload(SupervisorTree.root(), modId);
    }

    // ── Supervisor node builder ────────────────────────────────────────────────

    /**
     * Creates a {@link SupervisorNode} for {@code modId} using the registered
     * factory, and registers it in {@code tree}.
     *
     * @throws IllegalStateException if no factory is registered for this modId
     */
    public SupervisorNode createAndRegister(
            SupervisorTree tree,
            String modId,
            SupervisorNode.RestartStrategy strategy,
            int maxRestarts,
            long periodMs) {
        Supplier<SandboxedEntrypoint> factory = factories.get(modId);
        if (factory == null) {
            throw new IllegalStateException(
                "[HotReload] no factory registered for modId=" + modId);
        }
        SupervisorNode node = new SupervisorNode(modId, strategy, maxRestarts, periodMs, factory);
        tree.register(node);
        return node;
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    public long totalReloads() { return totalReloads.get(); }

    public ReloadRecord lastReload(String modId) { return history.get(modId); }

    public Map<String, ReloadRecord> reloadHistory() {
        return java.util.Collections.unmodifiableMap(history);
    }

    void resetForTests() {
        factories.clear();
        history.clear();
        totalReloads.set(0L);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void recordReload(String modId, boolean success, long elapsedMs, Throwable cause) {
        history.put(modId, new ReloadRecord(modId, success, elapsedMs,
            System.currentTimeMillis(), cause));
    }

    // ── Record types ──────────────────────────────────────────────────────────

    /**
     * Snapshot of the last reload attempt for a given mod.
     */
    public record ReloadRecord(
        String modId,
        boolean success,
        long elapsedMs,
        long timestampMs,
        Throwable cause
    ) {}
}
