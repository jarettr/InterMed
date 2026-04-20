package org.intermed.core.monitor;

import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reusable flyweight pool for event wrapper instances used on hot paths.
 *
 * <p>The fast path is thread-local to avoid global queue traffic during bursts,
 * while a bounded global pool still amortizes allocations across threads.
 */
public final class EventFlyweightPool {

    private static final int PREALLOCATED_GLOBAL = 512;
    private static final int MAX_GLOBAL_POOL_SIZE = 1024;
    private static final int MAX_LOCAL_POOL_SIZE = 32;

    private static final ConcurrentLinkedDeque<InterMedEvent> GLOBAL_POOL = new ConcurrentLinkedDeque<>();
    private static final ThreadLocal<ArrayDeque<InterMedEvent>> LOCAL_POOL =
        ThreadLocal.withInitial(() -> new ArrayDeque<>(MAX_LOCAL_POOL_SIZE));
    private static final AtomicInteger GLOBAL_SIZE = new AtomicInteger(0);

    private EventFlyweightPool() {}

    public static void preallocate() {
        while (GLOBAL_SIZE.get() < PREALLOCATED_GLOBAL) {
            GLOBAL_POOL.addFirst(new InterMedEvent());
            GLOBAL_SIZE.incrementAndGet();
        }
    }

    public static InterMedEvent acquire(String name, Object payload) {
        InterMedEvent event = acquireReusable();
        event.eventName = name;
        event.payload = payload;
        return event;
    }

    public static void release(InterMedEvent event) {
        if (event == null) {
            return;
        }
        event.reset();
        ArrayDeque<InterMedEvent> localPool = LOCAL_POOL.get();
        if (localPool.size() < MAX_LOCAL_POOL_SIZE) {
            localPool.addFirst(event);
            return;
        }
        if (GLOBAL_SIZE.get() < MAX_GLOBAL_POOL_SIZE) {
            GLOBAL_POOL.addFirst(event);
            GLOBAL_SIZE.incrementAndGet();
        }
    }

    static void resetForTests() {
        GLOBAL_POOL.clear();
        GLOBAL_SIZE.set(0);
        LOCAL_POOL.remove();
    }

    private static InterMedEvent acquireReusable() {
        ArrayDeque<InterMedEvent> localPool = LOCAL_POOL.get();
        InterMedEvent local = localPool.pollFirst();
        if (local != null) {
            return local;
        }
        InterMedEvent global = GLOBAL_POOL.pollFirst();
        if (global != null) {
            GLOBAL_SIZE.decrementAndGet();
            return global;
        }
        return new InterMedEvent();
    }

    public static final class InterMedEvent {
        public String eventName;
        public Object payload;

        public void reset() {
            eventName = null;
            payload = null;
        }
    }
}
