package net.fabricmc.fabric.api.event.lifecycle.v1;

import org.intermed.core.classloading.TransformationContext;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.monitor.ObservabilityMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTickEventsTest {

    @AfterEach
    void tearDown() {
        ServerTickEvents.resetForTests();
        ObservabilityMonitor.resetForTests();
        System.clearProperty("observability.ewma.threshold.ms");
        RuntimeConfig.resetForTests();
    }

    @Test
    void skipsThrottledTickListenersDuringDispatch() {
        AtomicInteger healthyCalls = new AtomicInteger();
        AtomicInteger throttledCalls = new AtomicInteger();

        try (TransformationContext.Scope ignored =
                 TransformationContext.enter("healthy_mod", "demo.HealthyTickListener")) {
            ServerTickEvents.START_SERVER_TICK.register(server -> healthyCalls.incrementAndGet());
        }
        try (TransformationContext.Scope ignored =
                 TransformationContext.enter("throttled_mod", "demo.ThrottledTickListener")) {
            ServerTickEvents.START_SERVER_TICK.register(server -> throttledCalls.incrementAndGet());
        }

        System.setProperty("observability.ewma.threshold.ms", "0.1");
        RuntimeConfig.reload();
        ObservabilityMonitor.onTickStart();
        ObservabilityMonitor.onTickEnd("throttled_mod");

        assertTrue(ObservabilityMonitor.isModThrottled("throttled_mod"));

        ServerTickEvents.START_SERVER_TICK.invoker().onStartTick(new Object());
        assertEquals(1, healthyCalls.get());
        assertEquals(0, throttledCalls.get());
        assertTrue(ServerTickEvents.START_SERVER_TICK.usesGeneratedDispatcher());
        assertTrue(ServerTickEvents.START_SERVER_TICK.dispatcherImplementationName()
            .contains("CompiledLockFreeEventDispatcher"));
    }
}
