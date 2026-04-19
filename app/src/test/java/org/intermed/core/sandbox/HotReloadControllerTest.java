package org.intermed.core.sandbox;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("strict-security")
class HotReloadControllerTest {

    @AfterEach
    void tearDown() {
        PolyglotSandboxManager.resetForTests();
    }

    @Test
    void triggerReloadCreatesNodeFromRegisteredFactory() {
        HotReloadController controller = HotReloadController.get();
        SupervisorTree tree = new SupervisorTree("reload-create");
        AtomicInteger created = new AtomicInteger();

        controller.register("reload_mod", () -> new TestEntrypoint("reload#" + created.incrementAndGet()));

        assertTrue(controller.triggerReload(tree, "reload_mod"));

        SupervisorNode node = tree.nodeFor("reload_mod");
        assertNotNull(node);
        assertEquals(1, created.get());
        assertEquals(1L, controller.totalReloads());
        assertTrue(controller.lastReload("reload_mod").success());
    }

    @Test
    void triggerReloadHotSwapsExistingNodeWithNewestFactory() {
        HotReloadController controller = HotReloadController.get();
        SupervisorTree tree = new SupervisorTree("reload-swap");
        AtomicInteger generation = new AtomicInteger();

        controller.register("swap_mod", () -> new TestEntrypoint("v" + generation.incrementAndGet()));
        SupervisorNode node = controller.createAndRegister(
            tree,
            "swap_mod",
            SupervisorNode.RestartStrategy.ONE_FOR_ONE,
            3,
            60_000L
        );
        TestEntrypoint first = (TestEntrypoint) node.child();
        controller.register("swap_mod", () -> new TestEntrypoint("v" + generation.incrementAndGet()));

        assertTrue(controller.triggerReload(tree, "swap_mod"));

        TestEntrypoint second = (TestEntrypoint) tree.nodeFor("swap_mod").child();
        assertNotSame(first, second);
        assertEquals("v2", second.id);
        assertEquals(1, first.teardownCalls);
        assertEquals(1L, controller.totalReloads());
    }

    private static final class TestEntrypoint implements SandboxedEntrypoint {
        private final String id;
        private int teardownCalls;

        private TestEntrypoint(String id) {
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
            teardownCalls++;
        }
    }
}
