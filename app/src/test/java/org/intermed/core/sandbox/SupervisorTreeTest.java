package org.intermed.core.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

@Tag("strict-security")
class SupervisorTreeTest {

    @AfterEach
    void tearDown() {
        PolyglotSandboxManager.resetForTests();
    }

    @Test
    void hotReloadRestartsNodeAndTearsDownPreviousChild() {
        SupervisorTree tree = new SupervisorTree("test-hot-reload");
        AtomicInteger created = new AtomicInteger();
        AtomicReference<FakeEntrypoint> first = new AtomicReference<>();
        AtomicReference<FakeEntrypoint> second = new AtomicReference<>();

        SupervisorNode node = new SupervisorNode(
            "demo_mod",
            SupervisorNode.RestartStrategy.ONE_FOR_ONE,
            3,
            60_000L,
            trackingFactory("demo_mod", created, first, second)
        );
        tree.register(node);

        FakeEntrypoint initial = first.get();
        assertNotNull(initial);
        assertTrue(tree.hotReload("demo_mod"));

        FakeEntrypoint reloaded = second.get();
        assertNotNull(reloaded);
        assertNotSame(initial, reloaded);
        assertEquals(1, initial.teardownCalls());
        assertSame(reloaded, tree.nodeFor("demo_mod").child());
        assertEquals(2, created.get());
    }

    @Test
    void oneForAllRestartsSourceAndLaterSiblings() {
        SupervisorTree tree = new SupervisorTree("test-one-for-all");
        AtomicInteger firstCreated = new AtomicInteger();
        AtomicInteger secondCreated = new AtomicInteger();
        AtomicReference<FakeEntrypoint> firstInitial = new AtomicReference<>();
        AtomicReference<FakeEntrypoint> secondInitial = new AtomicReference<>();

        SupervisorNode first = new SupervisorNode(
            "alpha",
            SupervisorNode.RestartStrategy.ONE_FOR_ALL,
            3,
            60_000L,
            trackingFactory("alpha", firstCreated, firstInitial, new AtomicReference<>())
        );
        SupervisorNode second = new SupervisorNode(
            "beta",
            SupervisorNode.RestartStrategy.ONE_FOR_ONE,
            3,
            60_000L,
            trackingFactory("beta", secondCreated, secondInitial, new AtomicReference<>())
        );

        tree.register(first);
        tree.register(second);

        assertTrue(first.notifyFailure(new IllegalStateException("boom")));

        assertEquals(1L, tree.oneForAllCount());
        assertEquals(2, firstCreated.get(), "source node should restart itself exactly once");
        assertEquals(2, secondCreated.get(), "sibling should be restarted by ONE_FOR_ALL");
        assertEquals(1, firstInitial.get().teardownCalls(), "failed source child must be torn down");
        assertEquals(1, secondInitial.get().teardownCalls(), "restarted sibling must be torn down once");
        assertEquals(List.of("alpha", "beta"), tree.nodes().stream().map(SupervisorNode::modId).toList());
    }

    @Test
    void escalatesAfterRestartIntensityIsExceeded() {
        SupervisorTree tree = new SupervisorTree("test-escalation");
        AtomicInteger created = new AtomicInteger();
        SupervisorNode node = new SupervisorNode(
            "unstable",
            SupervisorNode.RestartStrategy.ONE_FOR_ONE,
            1,
            60_000L,
            trackingFactory("unstable", created, new AtomicReference<>(), new AtomicReference<>())
        );
        tree.register(node);

        assertTrue(node.notifyFailure(new IllegalStateException("first")));
        assertFalse(node.notifyFailure(new IllegalStateException("second")));

        assertEquals(SupervisorNode.NodeState.STOPPED, node.state());
        assertEquals(1L, tree.escalationCount());
        assertEquals(2L, node.totalRestarts());
    }

    private static Supplier<SandboxedEntrypoint> trackingFactory(String modId,
                                                                 AtomicInteger created,
                                                                 AtomicReference<FakeEntrypoint> first,
                                                                 AtomicReference<FakeEntrypoint> second) {
        return () -> {
            FakeEntrypoint entrypoint = new FakeEntrypoint(modId + "#" + created.incrementAndGet());
            if (first.get() == null) {
                first.set(entrypoint);
            } else if (second.get() == null) {
                second.set(entrypoint);
            }
            return entrypoint;
        };
    }

    private static final class FakeEntrypoint implements SandboxedEntrypoint {
        private final String id;
        private final AtomicInteger teardownCalls = new AtomicInteger();

        private FakeEntrypoint(String id) {
            this.id = id;
        }

        @Override
        public SandboxExecutionResult lastSandboxResult() {
            return new SandboxExecutionResult(
                id,
                SandboxMode.NATIVE,
                SandboxMode.NATIVE,
                "main",
                id,
                true,
                false,
                "ok",
                "ok",
                "",
                "",
                "",
                List.of()
            );
        }

        @Override
        public void teardown() {
            teardownCalls.incrementAndGet();
        }

        int teardownCalls() {
            return teardownCalls.get();
        }
    }
}
