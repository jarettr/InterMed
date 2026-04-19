package net.fabricmc.fabric.api.networking.v1;

import org.intermed.core.monitor.EventRegistrationSupport;
import org.intermed.core.monitor.LockFreeEvent;

/**
 * Minimal Fabric-compatible connection event bus backed by InterMed bridges.
 */
public final class ServerPlayConnectionEvents {

    public static final LockFreeEvent<Init> INIT = new LockFreeEvent<>(
        Init.class,
        EventRegistrationSupport::captureRegistrationModId,
        publisher -> (handler, server) -> publisher.publish(handler, server, null)
    );
    public static final LockFreeEvent<Join> JOIN = new LockFreeEvent<>(
        Join.class,
        EventRegistrationSupport::captureRegistrationModId,
        publisher -> (handler, sender, server) -> publisher.publish(handler, sender, server)
    );

    public static final LockFreeEvent<Disconnect> DISCONNECT = new LockFreeEvent<>(
        Disconnect.class,
        EventRegistrationSupport::captureRegistrationModId,
        publisher -> (handler, server) -> publisher.publish(handler, server, null)
    );

    private ServerPlayConnectionEvents() {}

    public static void resetForTests() {
        INIT.clear();
        JOIN.clear();
        DISCONNECT.clear();
    }

    @FunctionalInterface
    public interface Init {
        void onPlayInit(Object handler, Object server);
    }

    @FunctionalInterface
    public interface Join {
        void onPlayReady(Object handler, Object sender, Object server);
    }

    @FunctionalInterface
    public interface Disconnect {
        void onPlayDisconnect(Object handler, Object server);
    }
}
