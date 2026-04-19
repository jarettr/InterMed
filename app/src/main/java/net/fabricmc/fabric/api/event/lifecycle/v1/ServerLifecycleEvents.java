package net.fabricmc.fabric.api.event.lifecycle.v1;

import org.intermed.core.monitor.EventRegistrationSupport;
import org.intermed.core.monitor.LockFreeEvent;

/**
 * Fabric-compatible server lifecycle event registry.
 *
 * <p>Events are fired by {@link org.intermed.core.bridge.FabricLifecycleBridge}
 * in response to the corresponding Forge / NeoForge server lifecycle events.
 *
 * <h3>Event order</h3>
 * <ol>
 *   <li>{@link #SERVER_STARTING} — server is initializing, world not yet loaded</li>
 *   <li>{@link #SERVER_STARTED} — world loaded, server accepting connections</li>
 *   <li>{@link #SERVER_STOPPING} — shutdown initiated, world still accessible</li>
 *   <li>{@link #SERVER_STOPPED} — world saved and closed</li>
 * </ol>
 */
public final class ServerLifecycleEvents {

    public static final LockFreeEvent<ServerStarting> SERVER_STARTING = new LockFreeEvent<>(
        ServerStarting.class,
        EventRegistrationSupport::captureRegistrationModId,
        publisher -> server -> publisher.publish(server, null, null)
    );

    public static final LockFreeEvent<ServerStarted> SERVER_STARTED = new LockFreeEvent<>(
        ServerStarted.class,
        EventRegistrationSupport::captureRegistrationModId,
        publisher -> server -> publisher.publish(server, null, null)
    );

    public static final LockFreeEvent<ServerStopping> SERVER_STOPPING = new LockFreeEvent<>(
        ServerStopping.class,
        EventRegistrationSupport::captureRegistrationModId,
        publisher -> server -> publisher.publish(server, null, null)
    );

    public static final LockFreeEvent<ServerStopped> SERVER_STOPPED = new LockFreeEvent<>(
        ServerStopped.class,
        EventRegistrationSupport::captureRegistrationModId,
        publisher -> server -> publisher.publish(server, null, null)
    );

    private ServerLifecycleEvents() {}

    public static void resetForTests() {
        SERVER_STARTING.clear();
        SERVER_STARTED.clear();
        SERVER_STOPPING.clear();
        SERVER_STOPPED.clear();
    }

    // ── Functional interfaces ─────────────────────────────────────────────────

    @FunctionalInterface
    public interface ServerStarting {
        void onServerStarting(Object server);
    }

    @FunctionalInterface
    public interface ServerStarted {
        void onServerStarted(Object server);
    }

    @FunctionalInterface
    public interface ServerStopping {
        void onServerStopping(Object server);
    }

    @FunctionalInterface
    public interface ServerStopped {
        void onServerStopped(Object server);
    }

}
