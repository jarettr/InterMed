package net.fabricmc.fabric.api.client.networking.v1;

import net.minecraft.resources.ResourceLocation;
import org.intermed.core.bridge.InterMedNetworkBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlayNetworkingTest {

    @AfterEach
    void tearDown() {
        InterMedNetworkBridge.resetForTests();
    }

    @Test
    void exposesTypedClientFramesAndChannelMetadata() {
        ResourceLocation channel = new ResourceLocation("demo", "client_text");
        AtomicReference<String> payload = new AtomicReference<>();

        assertTrue(ClientPlayNetworking.registerUtf8Receiver(channel,
            (client, handler, body, responseSender) -> payload.set(body)));

        byte[] frame = ClientPlayNetworking.createStringPayloadFrame(channel, "hello-client");

        assertTrue(ClientPlayNetworking.dispatchGlobalFrame(frame, new Object(), new Object(), new Object()));
        assertEquals("hello-client", payload.get());
        assertEquals(InterMedNetworkBridge.transportChannel(), ClientPlayNetworking.transportChannel());
        assertEquals(
            InterMedNetworkBridge.resolveChannelNumericId(channel, InterMedNetworkBridge.DeliveryDirection.CLIENTBOUND),
            ClientPlayNetworking.globalChannelId(channel)
        );
        assertEquals(1, ClientPlayNetworking.snapshotChannels().size());
    }

    @Test
    void rejectsNullTypedReceivers() {
        assertFalse(ClientPlayNetworking.registerUtf8Receiver(new ResourceLocation("demo", "missing"), null));
        assertFalse(ClientPlayNetworking.registerBinaryReceiver(new ResourceLocation("demo", "missing_bin"), null));
    }
}
