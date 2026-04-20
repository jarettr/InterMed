package net.fabricmc.fabric.api.command.v2;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandRegistrationCallbackTest {

    @AfterEach
    void tearDown() {
        CommandRegistrationCallback.resetForTests();
    }

    @Test
    void registersAndDispatchesCommandRegistrationCallbacks() {
        AtomicReference<CommandRegistrationCallback.RegistrationEnvironment> environment = new AtomicReference<>();

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, env) -> environment.set(env));

        CommandRegistrationCallback.EVENT.invoker().register(
            null,
            CommandRegistryAccess.empty(),
            CommandRegistrationCallback.RegistrationEnvironment.DEDICATED
        );

        assertEquals(CommandRegistrationCallback.RegistrationEnvironment.DEDICATED, environment.get());
        assertTrue(CommandRegistrationCallback.RegistrationEnvironment.ALL.dedicated());
        assertTrue(CommandRegistrationCallback.RegistrationEnvironment.ALL.integrated());
    }
}
