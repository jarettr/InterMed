package org.intermed.core.bridge;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeNetworkBridgeTest {

    @AfterEach
    void tearDown() {
        ForgeNetworkBridge.resetForTests();
        InterMedNetworkBridge.resetForTests();
    }

    @Test
    void routesForgeSimpleChannelMessagesAcrossUniversalEnvelope() {
        ResourceLocation channelId = new ResourceLocation("demo", "forge_main");
        ForgeNetworkBridge.SimpleChannelHandle channel = ForgeNetworkBridge.channel(channelId, "1");
        AtomicReference<String> clientPayload = new AtomicReference<>();
        AtomicReference<String> serverPayload = new AtomicReference<>();

        channel.registerClientboundUtf8(0, "demo", (client, handler, payload, responseSender) -> clientPayload.set(payload));
        channel.registerServerboundUtf8(1, "demo", (server, player, handler, payload, responseSender) -> serverPayload.set(payload));

        byte[] clientFrame = channel.createClientboundUtf8Frame(0, "demo", "forge-client");
        byte[] serverFrame = channel.createServerboundUtf8Frame(1, "demo", "forge-server");

        assertTrue(InterMedNetworkBridge.dispatchClientEnvelope(clientFrame, new Object(), new Object(), new Object()));
        assertTrue(InterMedNetworkBridge.dispatchServerEnvelope(serverFrame, new Object(), new Object(), new Object(), new Object()));
        assertEquals("forge-client", clientPayload.get());
        assertEquals("forge-server", serverPayload.get());
        assertTrue(channel.acceptsClientVersion("1"));
        assertFalse(channel.acceptsClientVersion("2"));
        assertEquals(1, ForgeNetworkBridge.snapshotChannels().size());
        assertEquals(2, ForgeNetworkBridge.snapshotMessages().size());
        assertEquals(2L, ForgeNetworkBridge.diagnostics().encodedMessages());
        assertEquals(2L, ForgeNetworkBridge.diagnostics().dispatchedMessages());
    }

    @Test
    void rejectsDuplicateDiscriminatorsPerDirection() {
        ResourceLocation channelId = new ResourceLocation("demo", "forge_dupe");
        ForgeNetworkBridge.SimpleChannelHandle channel = ForgeNetworkBridge.channel(channelId, "1");

        channel.registerClientboundUtf8(0, "demo", (client, handler, payload, responseSender) -> {});

        assertThrows(IllegalStateException.class, () ->
            channel.registerClientboundUtf8(0, "demo", (client, handler, payload, responseSender) -> {}));
    }
}
