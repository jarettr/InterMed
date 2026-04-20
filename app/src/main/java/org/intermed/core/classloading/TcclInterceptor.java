package org.intermed.core.classloading;

import org.intermed.core.security.CapabilityManager;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Thread-Context ClassLoader (TCCL) Interceptor (ТЗ 3.5.1, Requirement 2).
 *
 * <h3>Problem</h3>
 * Fabric/Forge mods schedule async work via {@link ExecutorService} and
 * {@link Thread} constructors. The new thread inherits its TCCL from the
 * <em>creating</em> thread, which in a javaagent context is often the system
 * classloader — not the mod's {@link LazyInterMedClassLoader} node. Any
 * class loading triggered inside the async task then bypasses the DAG and
 * falls back to the system classloader, breaking mod isolation.
 *
 * <h3>Solution</h3>
 * This class provides factory/wrapper utilities that capture the
 * {@link LazyInterMedClassLoader} of the <em>submitting</em> thread and
 * inject it as the TCCL of every spawned or submitted task. It also captures
 * the active capability mod context so security attribution survives pools
 * whose worker threads do not preserve the original TCCL.
 *
 * <h3>Integration</h3>
 * <ul>
 *   <li>Wrap {@link ExecutorService}s with {@link #wrap} at construction time
 *       inside mod entry-points or the lifecycle bootstrap.</li>
 *   <li>Use {@link #contextAwareFactory} when creating thread pools.</li>
 *   <li>All internal InterMed thread pools use this automatically.</li>
 * </ul>
 */
public final class TcclInterceptor {

    private static final AtomicLong INJECTIONS = new AtomicLong();

    private TcclInterceptor() {}

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns an {@link ExecutorService} wrapper that captures the calling
     * thread's {@link LazyInterMedClassLoader} (if present) and sets it as
     * TCCL for every submitted task.
     *
     * <p>If the calling thread's TCCL is not a {@link LazyInterMedClassLoader},
     * the wrapper is transparent (no TCCL modification).
     */
    public static ExecutorService wrap(ExecutorService delegate) {
        return new TcclPropagatingExecutor(delegate);
    }

    /**
     * Returns a {@link ScheduledExecutorService} wrapper that propagates the
     * submitting thread's TCCL for one-shot and recurring scheduled tasks.
     */
    public static ScheduledExecutorService wrap(ScheduledExecutorService delegate) {
        return new TcclPropagatingScheduledExecutor(delegate);
    }

    /**
     * Returns a {@link ThreadFactory} that sets the TCCL on each created
     * thread to the {@link LazyInterMedClassLoader} of the calling (creating)
     * thread, or falls back to the provided base factory.
     */
    public static ThreadFactory contextAwareFactory(ThreadFactory base) {
        Objects.requireNonNull(base, "base");
        return runnable -> {
            CapturedContext context = captureContext();
            Thread thread = base.newThread(context.wrap(Objects.requireNonNull(runnable, "runnable")));
            if (context.hasTccl()) {
                thread.setContextClassLoader(context.tccl());
            }
            if (!context.isEmpty()) {
                INJECTIONS.incrementAndGet();
            }
            return thread;
        };
    }

    /**
     * Convenience overload: wraps the default thread factory.
     */
    public static ThreadFactory contextAwareFactory() {
        return contextAwareFactory(Executors.defaultThreadFactory());
    }

    /**
     * Wraps a {@link Runnable} so that when it is executed the TCCL is set
     * to the {@link LazyInterMedClassLoader} of the <em>submitting</em> thread.
     * Use this to propagate mod context into {@link java.util.concurrent.ForkJoinPool}
     * or other pools that do not accept a ThreadFactory.
     */
    public static Runnable propagating(Runnable task) {
        Objects.requireNonNull(task, "task");
        CapturedContext context = captureContext();
        if (context.isEmpty()) {
            return task;
        }
        INJECTIONS.incrementAndGet();
        return context.wrap(task);
    }

    /**
     * Wraps a {@link Callable} with TCCL propagation from the submitting thread.
     */
    public static <V> Callable<V> propagating(Callable<V> task) {
        Objects.requireNonNull(task, "task");
        CapturedContext context = captureContext();
        if (context.isEmpty()) {
            return task;
        }
        INJECTIONS.incrementAndGet();
        return context.wrap(task);
    }

    /**
     * Wraps a {@link Supplier} with TCCL/capability propagation. This is the
     * shape used by {@link java.util.concurrent.CompletableFuture#supplyAsync}.
     */
    public static <V> Supplier<V> propagating(Supplier<V> task) {
        Objects.requireNonNull(task, "task");
        CapturedContext context = captureContext();
        if (context.isEmpty()) {
            return task;
        }
        INJECTIONS.incrementAndGet();
        return context.wrap(task);
    }

    /** Total number of TCCL injections performed since JVM start. */
    public static long injectionCount() { return INJECTIONS.get(); }

    static void resetForTests() {
        INJECTIONS.set(0);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Returns the nearest {@link LazyInterMedClassLoader} in the TCCL chain,
     * or {@code null} if the current TCCL is not mod-controlled.
     */
    static ClassLoader captureLazyModLoader(ClassLoader tccl) {
        ClassLoader cursor = tccl;
        while (cursor != null) {
            if (cursor instanceof LazyInterMedClassLoader) {
                return cursor;
            }
            cursor = cursor.getParent();
        }
        return null;
    }

    static String captureModId(ClassLoader capturedTccl) {
        String modId = CapabilityManager.currentModId();
        if ((modId == null || modId.isBlank()) && capturedTccl instanceof LazyInterMedClassLoader loader) {
            modId = loader.getNodeId();
        }
        return modId == null || modId.isBlank() ? null : modId;
    }

    private static CapturedContext captureContext() {
        ClassLoader capturedTccl = captureLazyModLoader(
            Thread.currentThread().getContextClassLoader());
        String modId = captureModId(capturedTccl);
        return new CapturedContext(capturedTccl, modId);
    }

    private record CapturedContext(ClassLoader tccl, String modId) {
        boolean hasTccl() {
            return tccl != null;
        }

        boolean hasModId() {
            return modId != null && !modId.isBlank();
        }

        boolean isEmpty() {
            return !hasTccl() && !hasModId();
        }

        Runnable wrap(Runnable task) {
            return () -> run(() -> {
                task.run();
                return null;
            });
        }

        <V> Callable<V> wrap(Callable<V> task) {
            return () -> call(task);
        }

        <V> Supplier<V> wrap(Supplier<V> task) {
            return () -> run(task::get);
        }

        private <V> V run(CheckedSupplier<V> task) {
            try {
                return call(task::get);
            } catch (RuntimeException | Error error) {
                throw error;
            } catch (Exception exception) {
                throw new AsyncContextException(exception);
            }
        }

        private <V> V call(Callable<V> task) throws Exception {
            ClassLoader previous = Thread.currentThread().getContextClassLoader();
            if (hasTccl()) {
                Thread.currentThread().setContextClassLoader(tccl);
            }
            try {
                if (hasModId()) {
                    return callAsMod(task);
                }
                return task.call();
            } finally {
                if (hasTccl()) {
                    Thread.currentThread().setContextClassLoader(previous);
                }
            }
        }

        private <V> V callAsMod(Callable<V> task) throws Exception {
            try {
                return CapabilityManager.executeAsMod(modId, () -> {
                    try {
                        return task.call();
                    } catch (RuntimeException | Error error) {
                        throw error;
                    } catch (Exception exception) {
                        throw new AsyncContextException(exception);
                    }
                });
            } catch (AsyncContextException exception) {
                throw exception.unwrap();
            }
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<V> {
        V get() throws Exception;
    }

    private static final class AsyncContextException extends RuntimeException {
        private AsyncContextException(Throwable cause) {
            super(cause);
        }

        private Exception unwrap() {
            Throwable cause = getCause();
            return cause instanceof Exception exception ? exception : this;
        }
    }

    // ── TcclPropagatingExecutor ───────────────────────────────────────────────

    /**
     * Transparent {@link ExecutorService} wrapper that injects TCCL into every
     * submitted task.
     */
    private static final class TcclPropagatingExecutor
            extends java.util.concurrent.AbstractExecutorService {

        private final ExecutorService delegate;

        TcclPropagatingExecutor(ExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(propagating(command));
        }

        @Override
        public void shutdown() { delegate.shutdown(); }

        @Override
        public java.util.List<Runnable> shutdownNow() { return delegate.shutdownNow(); }

        @Override
        public boolean isShutdown() { return delegate.isShutdown(); }

        @Override
        public boolean isTerminated() { return delegate.isTerminated(); }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit)
                throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }

    private static final class TcclPropagatingScheduledExecutor
            extends java.util.concurrent.AbstractExecutorService
            implements ScheduledExecutorService {

        private final ScheduledExecutorService delegate;

        TcclPropagatingScheduledExecutor(ScheduledExecutorService delegate) {
            this.delegate = delegate;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return delegate.schedule(propagating(command), delay, unit);
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            return delegate.schedule(propagating(callable), delay, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                                      long initialDelay,
                                                      long period,
                                                      TimeUnit unit) {
            return delegate.scheduleAtFixedRate(propagating(command), initialDelay, period, unit);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command,
                                                         long initialDelay,
                                                         long delay,
                                                         TimeUnit unit) {
            return delegate.scheduleWithFixedDelay(propagating(command), initialDelay, delay, unit);
        }

        @Override
        public void execute(Runnable command) {
            delegate.execute(propagating(command));
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
