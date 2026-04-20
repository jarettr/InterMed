package net.fabricmc.fabric.api.networking.v1;

import net.minecraft.resources.ResourceLocation;
import org.intermed.core.bridge.InterMedNetworkBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPlayNetworkingTest {

    @AfterEach
    void tearDown() {
        InterMedNetworkBridge.resetForTests();
    }

    @Test
    void exposesTypedServerFramesAndChannelMetadata() {
        ResourceLocation channel = new ResourceLocation("demo", "server_text");
        AtomicReference<String> payload = new AtomicReference<>();

        assertTrue(ServerPlayNetworking.registerUtf8Receiver(channel,
            (server, player, handler, body, responseSender) -> payload.set(body)));

        byte[] frame = ServerPlayNetworking.createStringPayloadFrame(channel, "hello-server");

        assertTrue(ServerPlayNetworking.dispatchGlobalFrame(frame, new Object(), new Object(), new Object(), new Object()));
        assertEquals("hello-server", payload.get());
        assertEquals(InterMedNetworkBridge.transportChannel(), ServerPlayNetworking.transportChannel());
        assertEquals(
            InterMedNetworkBridge.resolveChannelNumericId(channel, InterMedNetworkBridge.DeliveryDirection.SERVERBOUND),
            ServerPlayNetworking.globalChannelId(channel)
        );
        assertEquals(1, ServerPlayNetworking.snapshotChannels().size());
    }

    @Test
    void rejectsNullTypedReceivers() {
        assertFalse(ServerPlayNetworking.registerUtf8Receiver(new ResourceLocation("demo", "missing"), null));
        assertFalse(ServerPlayNetworking.registerBinaryReceiver(new ResourceLocation("demo", "missing_bin"), null));
    }
}
