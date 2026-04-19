package org.intermed.core.classloading;

import org.intermed.core.async.BackgroundPreparator;
import org.intermed.core.async.PreparatorTask;
import org.intermed.core.security.CapabilityManager;
import org.intermed.core.security.SecurityPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TcclInterceptorTest {

    @AfterEach
    void tearDown() {
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        TcclInterceptor.resetForTests();
        SecurityPolicy.resetForTests();
        CapabilityManager.resetForTests();
    }

    @Test
    void propagatesLazyLoaderIntoScheduledExecutors() throws Exception {
        LazyInterMedClassLoader loader = new LazyInterMedClassLoader(
            "tccl-scheduled", null, Set.of(), getClass().getClassLoader());
        AtomicReference<ClassLoader> captured = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        ScheduledExecutorService delegate = Executors.newSingleThreadScheduledExecutor();
        ScheduledExecutorService wrapped = TcclInterceptor.wrap(delegate);

        Thread.currentThread().setContextClassLoader(loader);
        wrapped.schedule(() -> {
            captured.set(Thread.currentThread().getContextClassLoader());
            latch.countDown();
        }, 0L, TimeUnit.MILLISECONDS);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertSame(loader, captured.get());
        wrapped.shutdownNow();
    }

    @Test
    void contextAwareFactoryPropagatesTcclAndCapabilityContextIntoThreads() throws Exception {
        LazyInterMedClassLoader loader = new LazyInterMedClassLoader(
            "tccl-thread", null, Set.of(), getClass().getClassLoader());
        AtomicReference<ClassLoader> capturedLoader = new AtomicReference<>();
        AtomicReference<String> capturedMod = new AtomicReference<>();

        Thread.currentThread().setContextClassLoader(loader);
        Thread thread = TcclInterceptor.contextAwareFactory(r -> new Thread(r, "tccl-thread-test"))
            .newThread(() -> {
                capturedLoader.set(Thread.currentThread().getContextClassLoader());
                capturedMod.set(CapabilityManager.currentModId());
            });

        thread.start();
        thread.join(5_000L);

        assertSame(loader, capturedLoader.get());
        assertTrue("tccl-thread".equals(capturedMod.get()));
    }

    @Test
    void executorWrapperPropagatesForcedCapabilityContext() throws Exception {
        ExecutorService delegate = Executors.newSingleThreadExecutor();
        ExecutorService wrapped = TcclInterceptor.wrap(delegate);
        try {
            String modId = CapabilityManager.executeAsMod("executor-mod", () -> {
                try {
                    return wrapped.submit(CapabilityManager::currentModId).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertTrue("executor-mod".equals(modId));
        } finally {
            wrapped.shutdownNow();
        }
    }

    @Test
    void completableFutureSupplierWrapperPropagatesForcedCapabilityContext() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            String modId = CapabilityManager.executeAsMod("future-mod", () -> {
                try {
                    Supplier<String> supplier = CapabilityManager::currentModId;
                    return CompletableFuture.supplyAsync(
                        TcclInterceptor.propagating(supplier),
                        executor
                    ).get(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            assertTrue("future-mod".equals(modId));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void backgroundPreparatorSubmissionsCarrySubmittingLoader() throws Exception {
        LazyInterMedClassLoader loader = new LazyInterMedClassLoader(
            "tccl-preparator", null, Set.of(), getClass().getClassLoader());
        AtomicReference<ClassLoader> captured = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        Thread.currentThread().setContextClassLoader(loader);
        BackgroundPreparator.getInstance().submitTask(new PreparatorTask() {
            @Override
            public int getPriority() {
                return 0;
            }

            @Override
            public void execute() {
                captured.set(Thread.currentThread().getContextClassLoader());
                latch.countDown();
            }

            @Override
            public String getName() {
                return "tccl-background";
            }
        });

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertSame(loader, captured.get());
    }
}
