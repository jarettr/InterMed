package net.fabricmc.fabric.api.event.player;

import net.minecraft.world.InteractionResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UseBlockCallbackTest {

    @AfterEach
    void tearDown() {
        UseBlockCallback.resetForTests();
    }

    @Test
    void dispatchesUntilFirstNonPassResult() {
        AtomicInteger calls = new AtomicInteger();

        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            calls.incrementAndGet();
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            calls.incrementAndGet();
            return InteractionResult.SUCCESS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            calls.incrementAndGet();
            return InteractionResult.FAIL;
        });

        assertEquals(InteractionResult.SUCCESS, UseBlockCallback.EVENT.invoker().interact(null, null, null, null));
        assertEquals(2, calls.get());
        assertEquals(3, UseBlockCallback.EVENT.listenerCount());
    }
}
