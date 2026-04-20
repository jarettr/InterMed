package org.intermed.core.async;

import org.intermed.core.classloading.TcclInterceptor;
import org.intermed.core.security.KernelContext;

import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Асинхронный движок подготовки модов.
 * Выполняет тяжелые задачи (PubGrub, AST-анализ) так, чтобы не вешать TPS и загрузку игры.
 */
public class BackgroundPreparator {

    private static final BackgroundPreparator INSTANCE = new BackgroundPreparator();
    private final ThreadPoolExecutor executor;

    private BackgroundPreparator() {
        // Создаем фабрику потоков с МИНИМАЛЬНЫМ приоритетом, чтобы не мешать основному потоку Minecraft
        ThreadFactory lowPriorityFactory = TcclInterceptor.contextAwareFactory(new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "InterMed-Preparator-" + counter.getAndIncrement());
                t.setPriority(Thread.MIN_PRIORITY); 
                t.setDaemon(true); // Демон-потоки не мешают закрытию игры
                return t;
            }
        });

        // Пул потоков с приоритетной очередью
        int cores = Math.max(1, Runtime.getRuntime().availableProcessors() / 2);
        this.executor = new ThreadPoolExecutor(
                cores, cores, 60L, TimeUnit.SECONDS,
                new PriorityBlockingQueue<>(), lowPriorityFactory
        );
    }

    public static BackgroundPreparator getInstance() {
        return INSTANCE;
    }

    public void submitTask(PreparatorTask task) {
        executor.execute(TcclInterceptor.propagating(() -> {
            System.out.println("\033[1;34m[Preparator] Starting background task: " + task.getName() + "\033[0m");
            try {
                // Все задачи Preparator'а выполняются в защищенном контуре ядра!
                KernelContext.executeWithException(() -> {
                    task.execute();
                    return null;
                });
                System.out.println("\033[1;32m[Preparator] Task completed: " + task.getName() + "\033[0m");
            } catch (Exception e) {
                System.err.println("\033[1;31m[Preparator] Task FAILED: " + task.getName() + "\033[0m");
                e.printStackTrace();
            }
        }));
    }

    public void shutdown() {
        executor.shutdown();
    }
}
