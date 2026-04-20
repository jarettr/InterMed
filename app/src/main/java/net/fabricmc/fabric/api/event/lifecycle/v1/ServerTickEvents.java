package net.fabricmc.fabric.api.event.lifecycle.v1;

import org.intermed.core.monitor.EventRegistrationSupport;
import org.intermed.core.monitor.LockFreeEvent;

/**
 * Minimal Fabric-compatible lifecycle registry for server tick callbacks.
 */
public final class ServerTickEvents {

    public static final LockFreeEvent<StartTick> START_SERVER_TICK = new LockFreeEvent<>(
        StartTick.class,
        EventRegistrationSupport::captureRegistrationModId,
        publisher -> server -> publisher.publish(server, null, null)
    );
    public static final LockFreeEvent<EndTick> END_SERVER_TICK = new LockFreeEvent<>(
        EndTick.class,
        EventRegistrationSupport::captureRegistrationModId,
        publisher -> server -> publisher.publish(server, null, null)
    );

    private ServerTickEvents() {}

    public static void resetForTests() {
        START_SERVER_TICK.clear();
        END_SERVER_TICK.clear();
    }

    @FunctionalInterface
    public interface StartTick {
        void onStartTick(Object server);
    }

    @FunctionalInterface
    public interface EndTick {
        void onEndTick(Object server);
    }
}
