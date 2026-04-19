package org.intermed.core.sandbox;

import org.intermed.core.config.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("strict-security")
class SandboxSharedExecutionContextTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("sandbox.shared.region.bytes");
        System.clearProperty("sandbox.shared.region.pool.max");
        RuntimeConfig.resetForTests();
        SandboxSharedExecutionContext.resetForTests();
    }

    @Test
    void bindsInvocationStateThroughSharedOffHeapFrame() {
        RuntimeConfig.resetForTests();

        try (SandboxSharedExecutionContext.ExecutionFrame frame = SandboxSharedExecutionContext.open(
            "shared_mod",
            "server",
            "demo.Target",
            SandboxMode.ESPRESSO,
            SandboxMode.ESPRESSO,
            "espresso-ready",
            false,
            true,
            false
        )) {
            String result = SandboxSharedExecutionContext.bind(frame, () -> {
                assertEquals("shared_mod", SandboxSharedExecutionContext.currentModId());
                assertEquals("server", SandboxSharedExecutionContext.currentInvocationKey());
                assertEquals("demo.Target", SandboxSharedExecutionContext.currentTarget());
                assertEquals("espresso-ready", SandboxSharedExecutionContext.currentPlanReason());
                assertEquals(1, SandboxSharedExecutionContext.currentRequestedModeId());
                assertEquals(1, SandboxSharedExecutionContext.currentEffectiveModeId());
                assertTrue(SandboxSharedExecutionContext.isCurrentRisky());
                assertTrue(SandboxSharedExecutionContext.currentSharedStateBytes() >= 64);
                assertTrue(
                    SandboxSharedExecutionContext.currentTransportKind().equals("direct-buffer")
                        || SandboxSharedExecutionContext.currentTransportKind().equals("ffm-shared-memory")
                );
                return SandboxSharedExecutionContext.currentTarget();
            });

            assertEquals("demo.Target", result);
        }
    }

    @Test
    void handlesLargeFramesWithDedicatedSharedMemoryRegion() {
        System.setProperty("sandbox.shared.region.bytes", "1024");
        RuntimeConfig.reload();

        String largeReason = "x".repeat(2048);
        try (SandboxSharedExecutionContext.ExecutionFrame frame = SandboxSharedExecutionContext.open(
            "large_shared_mod",
            "main",
            "demo.LargeTarget",
            SandboxMode.WASM,
            SandboxMode.WASM,
            largeReason,
            false,
            false,
            false
        )) {
            int bytesUsed = SandboxSharedExecutionContext.bind(frame, SandboxSharedExecutionContext::currentSharedStateBytes);
            assertTrue(bytesUsed > 2048);
        }
    }

    @Test
    void exposesStructuredSharedGraphThroughAccessors() {
        RuntimeConfig.resetForTests();

        SandboxSharedExecutionContext.SharedStateNode player = SandboxSharedExecutionContext.SharedStateNode
            .of("player", "Steve")
            .withProperty("dimension", "minecraft:overworld")
            .withProperty("health", "20")
            .withPosition(10.5d, 64.0d, -2.25d);

        SandboxSharedExecutionContext.SharedStateNode world = SandboxSharedExecutionContext.SharedStateNode
            .of("world", "overworld")
            .withProperty("difficulty", "hard")
            .withChild(player);

        SandboxSharedExecutionContext.SharedStateNode root = SandboxSharedExecutionContext.SharedStateNode
            .of("execution", "shared_mod")
            .withProperty("requestedSandbox", "espresso")
            .withProperty("effectiveSandbox", "espresso")
            .withChild(world);

        try (SandboxSharedExecutionContext.ExecutionFrame frame = SandboxSharedExecutionContext.open(
            "shared_mod",
            "server",
            "demo.Target",
            SandboxMode.ESPRESSO,
            SandboxMode.ESPRESSO,
            "espresso-ready",
            false,
            false,
            false,
            SandboxSharedExecutionContext.SharedStateGraph.ofRoot(root)
        )) {
            SandboxSharedExecutionContext.bind(frame, () -> {
                assertEquals(3, SandboxSharedExecutionContext.currentSharedGraphNodeCount());
                assertEquals("execution", SandboxSharedExecutionContext.currentSharedGraphRootType());
                assertEquals("shared_mod", SandboxSharedExecutionContext.currentSharedGraphRootName());
                assertEquals(1, SandboxSharedExecutionContext.currentSharedGraphNodeChildCount(0));
                assertEquals(1, SandboxSharedExecutionContext.currentSharedGraphNodeFirstChildIndex(0));
                assertEquals("world", SandboxSharedExecutionContext.currentSharedGraphNodeType(1));
                assertEquals("overworld", SandboxSharedExecutionContext.currentSharedGraphNodeName(1));
                assertEquals(0, SandboxSharedExecutionContext.currentSharedGraphNodeParentIndex(1));
                assertEquals("Steve", SandboxSharedExecutionContext.currentSharedGraphNodeName(2));
                assertEquals(1, SandboxSharedExecutionContext.currentSharedGraphNodeParentIndex(2));
                assertEquals("minecraft:overworld",
                    SandboxSharedExecutionContext.currentSharedGraphNodeProperty(2, "dimension"));
                assertEquals("20", SandboxSharedExecutionContext.currentSharedGraphNodeProperty(2, "health"));
                assertEquals(10.5d, SandboxSharedExecutionContext.currentSharedGraphNodeX(2));
                assertEquals(64.0d, SandboxSharedExecutionContext.currentSharedGraphNodeY(2));
                assertEquals(-2.25d, SandboxSharedExecutionContext.currentSharedGraphNodeZ(2));
                return null;
            });
        }
    }
}
