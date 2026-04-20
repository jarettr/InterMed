package org.intermed.core.monitor;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("microbench")
class LockFreeEventHotPathMicrobenchTest {

    @AfterEach
    void tearDown() {
        ServerTickEvents.resetForTests();
        ObservabilityMonitor.resetForTests();
    }

    @Test
    void writesLockFreeEventHotPathReport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger directCalls = new AtomicInteger();
        ServerTickEvents.START_SERVER_TICK.register(server -> calls.incrementAndGet());
        ServerTickEvents.StartTick baselineInvoker = server -> directCalls.incrementAndGet();

        Object server = new Object();
        ServerTickEvents.START_SERVER_TICK.invoker().onStartTick(server);

        long baselineStarted = System.nanoTime();
        for (int i = 0; i < 200_000; i++) {
            baselineInvoker.onStartTick(server);
        }
        long baselineElapsed = System.nanoTime() - baselineStarted;

        long started = System.nanoTime();
        for (int i = 0; i < 200_000; i++) {
            ServerTickEvents.START_SERVER_TICK.invoker().onStartTick(server);
        }
        long elapsedNanos = System.nanoTime() - started;

        double baselineNanosPerOp = baselineElapsed / 200_000.0d;
        double nanosPerOp = elapsedNanos / 200_000.0d;
        double overheadRatio = nanosPerOp / Math.max(1.0d, baselineNanosPerOp);
        String report = """
            event_bus_hot_path:
              iterations: 200000
              baseline_nanos_total: %d
              baseline_nanos_per_op: %.2f
              baseline_total_calls: %d
              total_calls: %d
              nanos_total: %d
              nanos_per_op: %.2f
              overhead_ratio_vs_direct_listener: %.4f
            """.formatted(
            baselineElapsed,
            baselineNanosPerOp,
            directCalls.get(),
            calls.get(),
            elapsedNanos,
            nanosPerOp,
            overheadRatio
        );

        Path outputDir = resolveOutputDir();
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("event-bus-hot-path.txt"), report);

        assertEquals(200_001, calls.get());
        assertTrue(overheadRatio <= maxRatioBudget(),
            "Lock-free event bus exceeded budget ratio: " + overheadRatio);
        assertTrue(nanosPerOp <= maxNanosBudget(),
            "Lock-free event bus exceeded absolute budget: " + nanosPerOp + " ns/op");
    }

    private static Path resolveOutputDir() {
        String configured = System.getProperty("intermed.microbench.outputDir");
        if (configured == null || configured.isBlank()) {
            return Path.of("build", "reports", "microbench");
        }
        return Path.of(configured);
    }

    private static double maxRatioBudget() {
        return Double.parseDouble(System.getProperty("intermed.budget.event.maxRatio", "6.0"));
    }

    private static double maxNanosBudget() {
        return Double.parseDouble(System.getProperty("intermed.budget.event.maxNanosPerOp", "500.0"));
    }
}
