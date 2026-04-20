package org.intermed.core.sandbox;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Hierarchical supervision tree for sandboxed mod isolation (ТЗ 3.5.7).
 *
 * <h3>Structure</h3>
 * A {@code SupervisorTree} owns a flat list of {@link SupervisorNode} children.
 * Trees may be nested: a failed node that exceeds its restart intensity is
 * <em>escalated</em> to the tree, which applies its own escalation policy.
 *
 * <h3>Restart coordination</h3>
 * <ul>
 *   <li>{@link SupervisorNode.RestartStrategy#ONE_FOR_ONE} — handled entirely
 *       within the node; the tree is only notified for diagnostics.</li>
 *   <li>{@link SupervisorNode.RestartStrategy#ONE_FOR_ALL} — when a node reports
 *       this strategy, the tree stops and restarts <em>all</em> nodes in the
 *       registration order.</li>
 *   <li>{@link SupervisorNode.RestartStrategy#REST_FOR_ONE} — the tree stops and
 *       restarts the failed node and every node registered <em>after</em> it.</li>
 * </ul>
 *
 * <h3>Hot reload</h3>
 * Restarting a node calls its {@code childFactory} which is allowed to supply a
 * freshly loaded {@link SandboxedEntrypoint} without restarting the JVM.  The
 * factory is responsible for re-acquiring any required resources.
 */
public final class SupervisorTree {

    private static final Logger LOG = Logger.getLogger(SupervisorTree.class.getName());

    /** Process-wide singleton tree. Additional per-feature subtrees can be created. */
    private static final SupervisorTree ROOT = new SupervisorTree("root");

    public static SupervisorTree root() { return ROOT; }

    static void resetRootForTests() {
        ROOT.resetForTests();
    }

    // ── Fields ─────────────────────────────────────────────────────────────────

    private final String name;
    /** Insertion-ordered list of nodes; protected by {@code this}. */
    private final List<SupervisorNode> nodes = new ArrayList<>();
    /** Fast lookup by modId. */
    private final Map<String, SupervisorNode> byModId = new ConcurrentHashMap<>();

    private final AtomicLong escalationCount   = new AtomicLong();
    private final AtomicLong oneForAllCount    = new AtomicLong();
    private final AtomicLong restForOneCount   = new AtomicLong();

    public SupervisorTree(String name) {
        this.name = name;
    }

    // ── Node registration ─────────────────────────────────────────────────────

    /**
     * Registers a node with this tree and starts it.
     *
     * @param node  the supervisor node to register; its owner is set to this tree
     * @throws IllegalArgumentException if a node for the same modId is already registered
     */
    public synchronized void register(SupervisorNode node) {
        if (byModId.containsKey(node.modId())) {
            throw new IllegalArgumentException(
                "SupervisorTree[" + name + "]: duplicate node for modId=" + node.modId());
        }
        node.setOwner(this);
        nodes.add(node);
        byModId.put(node.modId(), node);
        node.start();
        LOG.fine(() -> "[SupervisorTree:" + name + "] registered " + node.modId());
    }

    /**
     * Unregisters and stops the node for {@code modId}, if present.
     */
    public synchronized void unregister(String modId) {
        SupervisorNode node = byModId.remove(modId);
        if (node != null) {
            nodes.remove(node);
            node.stop();
            LOG.fine(() -> "[SupervisorTree:" + name + "] unregistered " + modId);
        }
    }

    /**
     * Returns the node for {@code modId}, or {@code null} if not registered.
     */
    public SupervisorNode nodeFor(String modId) {
        return byModId.get(modId);
    }

    /** Returns an unmodifiable snapshot of registered nodes in registration order. */
    public synchronized List<SupervisorNode> nodes() {
        return Collections.unmodifiableList(new ArrayList<>(nodes));
    }

    // ── Restart coordination (called by SupervisorNode) ───────────────────────

    /**
     * Called by a node when its restart strategy requires sibling coordination.
     * Runs on the calling thread (the thread that detected the failure).
     */
    void onRestartStrategy(SupervisorNode source,
                           SupervisorNode.RestartStrategy strategy) {
        switch (strategy) {
            case ONE_FOR_ALL  -> restartAll(source);
            case REST_FOR_ONE -> restartRestForOne(source);
            default -> { /* ONE_FOR_ONE handled in node itself */ }
        }
    }

    /**
     * Called when a node exceeds its restart intensity and escalates.
     */
    void onNodeEscalated(SupervisorNode node, Throwable cause) {
        escalationCount.incrementAndGet();
        LOG.log(Level.SEVERE,
            "[SupervisorTree:" + name + "] node escalated: " + node.modId(), cause);
        // Default policy: stop the escalated node so it does not destabilize others.
        // A parent SupervisorTree (if this tree is itself supervised) would handle
        // further escalation; for the root tree we simply mark it stopped.
        synchronized (this) {
            node.stop();
        }
    }

    // ── Hot reload ────────────────────────────────────────────────────────────

    /**
     * Forces a hot reload of the node for {@code modId}: stops the current child
     * and starts a fresh one via the node's childFactory.
     *
     * <p>This is the hot-reload path that does not require a JVM restart.
     *
     * @return {@code true} if the node was found and restarted
     */
    public boolean hotReload(String modId) {
        SupervisorNode node = byModId.get(modId);
        if (node == null) return false;
        LOG.info("[SupervisorTree:" + name + "] hot-reloading " + modId);
        node.stop();
        node.start();
        return true;
    }

    /**
     * Stops all registered nodes in reverse registration order (shutdown).
     */
    public synchronized void stopAll() {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            try {
                nodes.get(i).stop();
            } catch (Throwable t) {
                LOG.log(Level.WARNING, "Error stopping node " + nodes.get(i).modId(), t);
            }
        }
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    public String name()              { return name; }
    public long escalationCount()     { return escalationCount.get(); }
    public long oneForAllCount()      { return oneForAllCount.get(); }
    public long restForOneCount()     { return restForOneCount.get(); }

    public synchronized int nodeCount() { return nodes.size(); }

    public synchronized long totalRestarts() {
        return nodes.stream().mapToLong(SupervisorNode::totalRestarts).sum();
    }

    synchronized void resetForTests() {
        stopAll();
        nodes.clear();
        byModId.clear();
        escalationCount.set(0L);
        oneForAllCount.set(0L);
        restForOneCount.set(0L);
    }

    // ── Internal restart helpers ──────────────────────────────────────────────

    private synchronized void restartAll(SupervisorNode source) {
        oneForAllCount.incrementAndGet();
        LOG.info("[SupervisorTree:" + name + "] ONE_FOR_ALL triggered by " + source.modId());
        // Stop all in reverse order
        for (int i = nodes.size() - 1; i >= 0; i--) {
            SupervisorNode n = nodes.get(i);
            if (n != source) {
                try { n.stop(); } catch (Throwable t) { LOG.log(Level.WARNING, "stop error", t); }
            }
        }
        // Start all in forward order
        for (SupervisorNode n : nodes) {
            try { n.start(); } catch (Throwable t) { LOG.log(Level.WARNING, "start error", t); }
        }
    }

    private synchronized void restartRestForOne(SupervisorNode source) {
        restForOneCount.incrementAndGet();
        int idx = nodes.indexOf(source);
        if (idx < 0) return;
        LOG.info("[SupervisorTree:" + name + "] REST_FOR_ONE triggered by "
            + source.modId() + " (index=" + idx + ")");
        // Stop all nodes after (and including) the failed node, in reverse order
        for (int i = nodes.size() - 1; i >= idx; i--) {
            SupervisorNode n = nodes.get(i);
            try { n.stop(); } catch (Throwable t) { LOG.log(Level.WARNING, "stop error", t); }
        }
        // Restart in forward order from the failed node
        for (int i = idx; i < nodes.size(); i++) {
            SupervisorNode n = nodes.get(i);
            try { n.start(); } catch (Throwable t) { LOG.log(Level.WARNING, "start error", t); }
        }
    }
}
