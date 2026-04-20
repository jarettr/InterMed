package net.fabricmc.fabric.api.command.v2;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import org.intermed.core.monitor.EventRegistrationSupport;
import org.intermed.core.monitor.LockFreeEvent;

public interface CommandRegistrationCallback {

    LockFreeEvent<CommandRegistrationCallback> EVENT = new LockFreeEvent<>(
        CommandRegistrationCallback.class,
        EventRegistrationSupport::captureRegistrationModId,
        publisher -> (dispatcher, registryAccess, environment) ->
            publisher.publish(dispatcher, registryAccess, environment)
    );

    void register(CommandDispatcher<CommandSourceStack> dispatcher,
                  CommandRegistryAccess registryAccess,
                  RegistrationEnvironment environment);

    static void resetForTests() {
        EVENT.clear();
    }

    enum RegistrationEnvironment {
        ALL(true, true),
        DEDICATED(false, true),
        INTEGRATED(true, false);

        private final boolean integrated;
        private final boolean dedicated;

        RegistrationEnvironment(boolean integrated, boolean dedicated) {
            this.integrated = integrated;
            this.dedicated = dedicated;
        }

        public boolean integrated() {
            return integrated;
        }

        public boolean dedicated() {
            return dedicated;
        }
    }
}
