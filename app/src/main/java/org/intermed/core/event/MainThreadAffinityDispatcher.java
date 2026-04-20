package org.intermed.core.event;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Thread-affinity policy over the InterMed event ring buffer (ТЗ 3.5.2).
 *
 * <h3>Problem</h3>
 * The OpenGL / Vulkan rendering context is strictly single-threaded (the
 * "main thread" / "render thread").  If render-related callbacks are dispatched
 * from the CAS ring-buffer drain loop on an arbitrary thread they corrupt the
 * GL context, causing artifacts or driver crashes.
 *
 * <h3>Solution</h3>
 * Events are classified as either {@link EventAffinity#RENDER} or
 * {@link EventAffinity#LOGIC}:
 * <ul>
 *   <li><b>RENDER</b> — enqueued into a lock-free queue; drained
 *       synchronously on the registered main thread at
 *       {@linkplain #drainMainThreadQueue()} (called from
 *       {@code InterMedEventBridge.onServerTick} START / render-tick hook).</li>
 *   <li><b>LOGIC</b> — dispatched immediately on the calling thread via the
 *       ring-buffer CAS path (existing {@link org.intermed.core.monitor.LockFreeEvent}
 *       behaviour).</li>
 * </ul>
 *
 * <h3>Registration</h3>
 * The main thread registers itself by calling {@link #registerMainThread()}.
 * Event producers classify their events via {@link #dispatch(EventAffinity, Runnable)}.
 */
public final class MainThreadAffinityDispatcher {

    /** Affinity classification for an event. */
    public enum EventAffinity {
        /** Must execute on the registered main/render thread. */
        RENDER,
        /** May execute on any thread via the ring-buffer CAS path. */
        LOGIC
    }

    private static final AtomicReference<Thread> MAIN_THREAD = new AtomicReference<>();
    private static final Queue<Runnable> MAIN_THREAD_QUEUE   = new ConcurrentLinkedQueue<>();

    private static final AtomicLong RENDER_DISPATCHED  = new AtomicLong();
    private static final AtomicLong RENDER_DROPPED     = new AtomicLong();
    private static final AtomicLong LOGIC_DISPATCHED   = new AtomicLong();
    private static final AtomicLong MAIN_THREAD_DRAINS = new AtomicLong();

    /** Maximum events drained per {@link #drainMainThreadQueue()} call. */
    private static final int MAX_DRAIN_PER_TICK =
        Integer.getInteger("intermed.event.affinity.max-drain-per-tick", 4096);

    private MainThreadAffinityDispatcher() {}

    // ── Registration ──────────────────────────────────────────────────────────

    /**
     * Registers the calling thread as the main/render thread.
     * Must be called exactly once, from the render/main thread.
     */
    public static void registerMainThread() {
        MAIN_THREAD.set(Thread.currentThread());
    }

    /** Returns {@code true} if the calling thread is the registered main thread. */
    public static boolean isMainThread() {
        return Thread.currentThread() == MAIN_THREAD.get();
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    /**
     * Routes an event according to its affinity.
     *
     * <ul>
     *   <li>{@code RENDER}: enqueued for main-thread processing.</li>
     *   <li>{@code LOGIC}: dispatched synchronously (inline on the calling thread,
     *       consistent with the ring-buffer drain path).</li>
     * </ul>
     *
     * @param affinity  the thread-affinity classification
     * @param task      the dispatch closure
     */
    public static void dispatch(EventAffinity affinity, Runnable task) {
        if (affinity == EventAffinity.RENDER) {
            if (isMainThread()) {
                // Already on the render thread — execute inline.
                safeRun(task);
            } else {
                MAIN_THREAD_QUEUE.offer(task);
            }
            RENDER_DISPATCHED.incrementAndGet();
        } else {
            task.run();
            LOGIC_DISPATCHED.incrementAndGet();
        }
    }

    /**
     * Convenience overload that executes a consumer with a typed argument.
     *
     * @param affinity  thread-affinity classification
     * @param listener  the consumer to invoke
     * @param event     the event object
     * @param <T>       the event type
     */
    public static <T> void dispatch(EventAffinity affinity, Consumer<T> listener, T event) {
        dispatch(affinity, () -> listener.accept(event));
    }

    // ── Main-thread drain ─────────────────────────────────────────────────────

    /**
     * Drains pending RENDER-affinity events on the <em>current</em> (main) thread.
     *
     * <p>Call this from the render-tick hook (start/end of each client tick) or
     * from {@code InterMedEventBridge.onServerTick} START phase.  The drain is
     * bounded by {@link #MAX_DRAIN_PER_TICK} to prevent one-tick overload.
     *
     * @return the number of events drained
     */
    public static int drainMainThreadQueue() {
        int drained = 0;
        Runnable task;
        while (drained < MAX_DRAIN_PER_TICK && (task = MAIN_THREAD_QUEUE.poll()) != null) {
            safeRun(task);
            drained++;
        }
        if (drained > 0) {
            MAIN_THREAD_DRAINS.incrementAndGet();
        }
        return drained;
    }

    /**
     * Returns the number of RENDER-affinity events currently waiting in the queue.
     * Non-zero values after repeated {@link #drainMainThreadQueue()} calls indicate
     * that the main thread is behind.
     */
    public static int pendingRenderEvents() {
        return MAIN_THREAD_QUEUE.size();
    }

    // ── Diagnostics ───────────────────────────────────────────────────────────

    public static long renderDispatched()   { return RENDER_DISPATCHED.get(); }
    public static long renderDropped()      { return RENDER_DROPPED.get(); }
    public static long logicDispatched()    { return LOGIC_DISPATCHED.get(); }
    public static long mainThreadDrains()   { return MAIN_THREAD_DRAINS.get(); }

    public static void resetForTests() {
        MAIN_THREAD.set(null);
        MAIN_THREAD_QUEUE.clear();
        RENDER_DISPATCHED.set(0);
        RENDER_DROPPED.set(0);
        LOGIC_DISPATCHED.set(0);
        MAIN_THREAD_DRAINS.set(0);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static void safeRun(Runnable task) {
        try {
            task.run();
        } catch (Throwable t) {
            System.err.printf("[ThreadAffinity] Exception in RENDER event: %s: %s%n",
                t.getClass().getSimpleName(), t.getMessage());
        }
    }
}
