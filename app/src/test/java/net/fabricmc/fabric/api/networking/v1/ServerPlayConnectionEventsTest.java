package net.fabricmc.fabric.api.networking.v1;

import org.intermed.core.classloading.TransformationContext;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.monitor.ObservabilityMonitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPlayConnectionEventsTest {

    @AfterEach
    void tearDown() {
        ServerPlayConnectionEvents.resetForTests();
        ObservabilityMonitor.resetForTests();
        System.clearProperty("observability.ewma.threshold.ms");
        RuntimeConfig.resetForTests();
    }

    @Test
    void skipsThrottledConnectionListenersDuringDispatch() {
        AtomicInteger healthyCalls = new AtomicInteger();
        AtomicInteger throttledCalls = new AtomicInteger();

        try (TransformationContext.Scope ignored =
                 TransformationContext.enter("healthy_join_mod", "demo.HealthyJoinListener")) {
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> healthyCalls.incrementAndGet());
        }
        try (TransformationContext.Scope ignored =
                 TransformationContext.enter("throttled_join_mod", "demo.ThrottledJoinListener")) {
            ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> throttledCalls.incrementAndGet());
        }

        System.setProperty("observability.ewma.threshold.ms", "0.1");
        RuntimeConfig.reload();
        ObservabilityMonitor.onTickStart();
        ObservabilityMonitor.onTickEnd("throttled_join_mod");

        assertTrue(ObservabilityMonitor.isModThrottled("throttled_join_mod"));

        ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(new Object(), new Object(), new Object());
        assertEquals(1, healthyCalls.get());
        assertEquals(0, throttledCalls.get());
    }
}
