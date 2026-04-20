package net.fabricmc.fabric.api.event.lifecycle.v1;

import org.intermed.core.monitor.EventRegistrationSupport;
import org.intermed.core.monitor.LockFreeEvent;

/**
 * Fabric-compatible world (level) lifecycle event registry.
 *
 * <p>Events are fired by {@link org.intermed.core.bridge.FabricLifecycleBridge}
 * in response to Forge/NeoForge {@code LevelEvent.Load} and {@code LevelEvent.Unload}.
 */
public final class ServerWorldEvents {

    /** Fired after a world/level has been loaded and is ready for gameplay. */
    public static final LockFreeEvent<Load> LOAD = new LockFreeEvent<>(
        Load.class,
        EventRegistrationSupport::captureRegistrationModId,
        publisher -> (server, world) -> publisher.publish(server, world, null)
    );

    /** Fired before a world/level is unloaded (e.g. on server stop or dimension switch). */
    public static final LockFreeEvent<Unload> UNLOAD = new LockFreeEvent<>(
        Unload.class,
        EventRegistrationSupport::captureRegistrationModId,
        publisher -> (server, world) -> publisher.publish(server, world, null)
    );

    private ServerWorldEvents() {}

    public static void resetForTests() {
        LOAD.clear();
        UNLOAD.clear();
    }

    // ── Functional interfaces ─────────────────────────────────────────────────

    @FunctionalInterface
    public interface Load {
        /**
         * @param server the Minecraft server instance
         * @param world  the loaded ServerLevel / ServerWorld
         */
        void onWorldLoad(Object server, Object world);
    }

    @FunctionalInterface
    public interface Unload {
        /**
         * @param server the Minecraft server instance
         * @param world  the unloading ServerLevel / ServerWorld
         */
        void onWorldUnload(Object server, Object world);
    }

}
