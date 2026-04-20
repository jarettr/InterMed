package org.intermed.core.lifecycle;

import org.intermed.core.classloading.TcclInterceptor;

import java.io.File;
import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Watches a directory for mod JAR additions, modifications, and removals and
 * triggers an incremental DAG rebuild on each detected change.
 *
 * <h3>Debounce</h3>
 * Raw filesystem events are buffered for {@value #DEBOUNCE_MS} ms before the
 * callback fires.  This coalesces rapid bursts (e.g. copying a multi-file mod
 * update) into a single reload event.
 *
 * <h3>Hot-reload semantics</h3>
 * <ul>
 *   <li><b>New JAR</b> — a fresh mod ClassLoader is built and appended to the DAG;
 *       the mod's {@code ModInitializer} entrypoints are invoked once.</li>
 *   <li><b>Modified JAR</b> — the existing ClassLoader for the affected mod ID is
 *       marked stale; the next class-load from it triggers a JAR re-parse.  Static
 *       mod state already in the JVM is <em>not</em> reset — a full restart is
 *       required for that.</li>
 *   <li><b>Removed JAR</b> — the ClassLoader is evicted from the active DAG; future
 *       class-load requests will throw {@link ClassNotFoundException}.  Instances
 *       already in memory remain alive until GC collects them.</li>
 * </ul>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * HotReloadManager mgr = HotReloadManager.start(
 *     Paths.get("mods"),
 *     deltas -> LifecycleManager.applyHotReloadDeltas(deltas)
 * );
 * // … game loop …
 * mgr.stop();
 * }</pre>
 */
public final class HotReloadManager {

    /** Debounce window in milliseconds. */
    private static final long DEBOUNCE_MS = 300L;

    // ── state ─────────────────────────────────────────────────────────────────

    private final Path watchedDirectory;
    private final Consumer<List<DagDelta>> onDelta;
    private final WatchService watchService;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Guarded by {@code this}. */
    private final List<File> pendingFiles = new ArrayList<>();
    private volatile ScheduledFuture<?> pendingFlush;
    private Thread watchThread;

    // ── construction / lifecycle ───────────────────────────────────────────────

    private HotReloadManager(Path directory, Consumer<List<DagDelta>> onDelta) throws IOException {
        this.watchedDirectory = directory;
        this.onDelta          = onDelta;
        this.watchService     = FileSystems.getDefault().newWatchService();
        this.scheduler        = TcclInterceptor.wrap(Executors.newSingleThreadScheduledExecutor(
            TcclInterceptor.contextAwareFactory(r -> {
            Thread t = new Thread(r, "intermed-hotreload-debounce");
            t.setDaemon(true);
            return t;
        })));
    }

    /**
     * Creates and starts a {@link HotReloadManager} that watches {@code directory}.
     *
     * @param directory directory to monitor (e.g. {@code Paths.get("mods")})
     * @param onDelta   callback invoked on the debounce thread with the detected deltas
     * @return the running manager; call {@link #stop()} to release resources
     * @throws IOException if the WatchService cannot register the directory
     */
    public static HotReloadManager start(Path directory,
                                         Consumer<List<DagDelta>> onDelta) throws IOException {
        HotReloadManager mgr = new HotReloadManager(directory, onDelta);
        mgr.register();
        mgr.startWatchThread();
        return mgr;
    }

    /**
     * Stops the watch thread and releases the underlying {@link WatchService}.
     * Pending debounced events are discarded.
     */
    public void stop() {
        running.set(false);
        try {
            watchService.close();
        } catch (IOException e) {
            System.err.printf("[HotReload] Failed to close WatchService: %s%n", e.getMessage());
        }
        scheduler.shutdownNow();
        Thread t = watchThread;
        if (t != null) {
            t.interrupt();
        }
        System.out.printf("[HotReload] Stopped watching %s.%n", watchedDirectory);
    }

    /** Returns {@code true} while the watcher is running. */
    public boolean isRunning() {
        return running.get();
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private void register() throws IOException {
        watchedDirectory.register(
            watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE
        );
        System.out.printf("[HotReload] Watching %s for mod JAR changes.%n", watchedDirectory);
    }

    private void startWatchThread() {
        running.set(true);
        watchThread = TcclInterceptor.contextAwareFactory(r -> {
            Thread t = new Thread(r, "intermed-hotreload-watcher");
            t.setDaemon(true);
            return t;
        }).newThread(this::watchLoop);
        watchThread.start();
    }

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take(); // blocks until an event arrives
            } catch (ClosedWatchServiceException | InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            for (WatchEvent<?> rawEvent : key.pollEvents()) {
                WatchEvent.Kind<?> kind = rawEvent.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    System.err.println("[HotReload] WatchService overflow — some events may have been lost");
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> event    = (WatchEvent<Path>) rawEvent;
                Path              context = event.context();
                if (context == null) continue;

                Path resolved = watchedDirectory.resolve(context);
                if (!resolved.toString().endsWith(".jar")) continue;

                synchronized (this) {
                    File file = resolved.toFile();
                    if (!pendingFiles.contains(file)) {
                        pendingFiles.add(file);
                    }
                }
                scheduleFlush();
            }

            if (!key.reset()) {
                System.err.println("[HotReload] WatchKey became invalid — directory may have been deleted");
                break;
            }
        }
    }

    private synchronized void scheduleFlush() {
        if (pendingFlush != null && !pendingFlush.isDone()) {
            pendingFlush.cancel(false);
        }
        pendingFlush = scheduler.schedule(this::flush, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private void flush() {
        List<File> snapshot;
        synchronized (this) {
            if (pendingFiles.isEmpty()) return;
            snapshot = new ArrayList<>(pendingFiles);
            pendingFiles.clear();
        }

        List<DagDelta> deltas = new ArrayList<>(snapshot.size());
        for (File file : snapshot) {
            DagDelta.Kind kind = file.exists()
                ? DagDelta.Kind.ADDED_OR_MODIFIED
                : DagDelta.Kind.REMOVED;
            deltas.add(new DagDelta(file, kind));
            System.out.printf("[HotReload] Detected %s: %s%n", kind, file.getName());
        }

        try {
            onDelta.accept(deltas);
        } catch (Exception e) {
            System.err.printf("[HotReload] Reload callback threw: %s%n", e.getMessage());
            e.printStackTrace();
        }
    }

    // ── public types ──────────────────────────────────────────────────────────

    /**
     * A single detected filesystem change affecting one mod JAR.
     *
     * @param file the JAR file that changed
     * @param kind whether the file was added/modified or removed
     */
    public record DagDelta(File file, Kind kind) {

        /** Coarse change classification. */
        public enum Kind {
            /** The file was created or its content was updated. */
            ADDED_OR_MODIFIED,
            /** The file no longer exists on the filesystem. */
            REMOVED
        }
    }
}
