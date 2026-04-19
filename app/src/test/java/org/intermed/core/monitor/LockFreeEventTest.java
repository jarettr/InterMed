package org.intermed.core.monitor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockFreeEventTest {

    @BeforeEach
    void setUp() {
        System.clearProperty("intermed.events.dispatcher-cache.max-size");
        LockFreeEvent.resetGeneratedDispatcherCacheForTests();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("intermed.events.dispatcher-cache.max-size");
        LockFreeEvent.resetGeneratedDispatcherCacheForTests();
    }

    @Test
    void publishesAcrossRingWrapAroundWithoutDroppingEvents() {
        List<Integer> observed = new ArrayList<>();
        LockFreeEvent<LockFreeEventValueListener> event = LockFreeEvent.createForTests(
            4,
            LockFreeEventValueListener.class,
            () -> "test_mod",
            publisher -> value -> publisher.publish(value, null, null)
        );
        event.register(observed::add);

        for (int i = 0; i < 32; i++) {
            event.invoker().onValue(i);
        }

        assertEquals(32, observed.size());
        for (int i = 0; i < observed.size(); i++) {
            assertEquals(i, observed.get(i));
        }
        assertTrue(event.usesGeneratedDispatcher());
        assertTrue(event.dispatcherImplementationName().contains("CompiledLockFreeEventDispatcher"));
        assertEquals(31L, event.publishedSequence());
        assertEquals(31L, event.consumedSequence());
    }

    @Test
    void supportsConcurrentPublishersWithoutDroppingEvents() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LockFreeEvent<LockFreeEventValueListener> event = LockFreeEvent.createForTests(
            32,
            LockFreeEventValueListener.class,
            () -> "concurrent_mod",
            publisher -> value -> publisher.publish(value, null, null)
        );
        event.register(value -> calls.incrementAndGet());

        int threads = 4;
        int iterationsPerThread = 500;
        CountDownLatch start = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(threads)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        event.invoker().onValue(i);
                    }
                    return null;
                }));
            }

            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        assertEquals(threads * iterationsPerThread, calls.get());
        assertEquals(event.publishedSequence(), event.consumedSequence());
    }

    @Test
    void registrationAfterPublicationSnapshotOnlyAffectsFutureEvents() throws Exception {
        AtomicInteger earlyCalls = new AtomicInteger();
        AtomicInteger lateCalls = new AtomicInteger();
        CountDownLatch stored = new CountDownLatch(1);
        CountDownLatch releaseDrain = new CountDownLatch(1);
        LockFreeEvent<LockFreeEventValueListener> event = LockFreeEvent.createForTests(
            8,
            LockFreeEventValueListener.class,
            () -> "snapshot_mod",
            sequence -> {
                stored.countDown();
                try {
                    if (!releaseDrain.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting to release publication drain");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Interrupted while waiting to release publication drain", e);
                }
            },
            publisher -> value -> publisher.publish(value, null, null)
        );
        event.register(value -> earlyCalls.incrementAndGet());

        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> future = executor.submit(() -> {
                event.invoker().onValue(1);
                return null;
            });
            assertTrue(stored.await(5, TimeUnit.SECONDS));

            event.register(value -> lateCalls.incrementAndGet());
            releaseDrain.countDown();
            future.get(5, TimeUnit.SECONDS);
        }

        assertEquals(1, earlyCalls.get());
        assertEquals(0, lateCalls.get());

        event.invoker().onValue(2);

        assertEquals(2, earlyCalls.get());
        assertEquals(1, lateCalls.get());
    }

    @Test
    void clearResetsSequencesAndAllowsFreshPublicationCycle() {
        AtomicInteger calls = new AtomicInteger();
        LockFreeEvent<LockFreeEventValueListener> event = LockFreeEvent.createForTests(
            8,
            LockFreeEventValueListener.class,
            () -> "reset_mod",
            publisher -> value -> publisher.publish(value, null, null)
        );
        event.register(value -> calls.incrementAndGet());

        event.invoker().onValue(1);
        event.clear();
        event.register(value -> calls.incrementAndGet());
        event.invoker().onValue(2);

        assertEquals(2, calls.get());
        assertTrue(event.usesGeneratedDispatcher());
        assertTrue(event.publishedSequence() >= 0);
        assertEquals(event.publishedSequence(), event.consumedSequence());
    }

    @Test
    void fallsBackToInterpretedPlanWhenGeneratedDispatcherCacheIsFull() {
        System.setProperty("intermed.events.dispatcher-cache.max-size", "2");
        LockFreeEvent<LockFreeEventValueListener> event = LockFreeEvent.createForTests(
            8,
            LockFreeEventValueListener.class,
            () -> "cache_mod",
            publisher -> value -> publisher.publish(value, null, null)
        );

        event.register(value -> {});
        assertTrue(event.usesGeneratedDispatcher());

        event.register(value -> {});
        assertTrue(event.usesGeneratedDispatcher());

        event.register(value -> {});
        assertFalse(event.usesGeneratedDispatcher());
        assertEquals(2, LockFreeEvent.generatedDispatcherCacheSizeForTests());
    }

    @Test
    void fallsBackToInterpretedPlanForPrivateListenerTypes() {
        AtomicInteger calls = new AtomicInteger();
        LockFreeEvent<PrivateValueListener> event = LockFreeEvent.createForTests(
            8,
            PrivateValueListener.class,
            () -> "private_mod",
            publisher -> value -> publisher.publish(value, null, null)
        );
        event.register(value -> calls.incrementAndGet());

        event.invoker().onValue(7);

        assertEquals(1, calls.get());
        assertFalse(event.usesGeneratedDispatcher());
    }

    @FunctionalInterface
    private interface PrivateValueListener {
        void onValue(int value);
    }
}
