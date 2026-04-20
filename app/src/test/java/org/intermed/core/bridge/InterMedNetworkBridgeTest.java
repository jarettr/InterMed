package org.intermed.core.bridge;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.resources.ResourceLocation;
import org.intermed.core.registry.RegistryTranslationMatrix;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InterMedNetworkBridgeTest {

    @AfterEach
    void tearDown() {
        InterMedNetworkBridge.resetForTests();
        ServerLifecycleEvents.resetForTests();
        ServerPlayConnectionEvents.resetForTests();
    }

    @Test
    void registersAndDispatchesClientReceivers() {
        ResourceLocation channel = new ResourceLocation("demo", "sync");
        AtomicReference<Object> receivedPayload = new AtomicReference<>();

        assertTrue(InterMedNetworkBridge.registerClientReceiver(channel,
            (client, handler, buf, responseSender) -> receivedPayload.set(buf)));
        assertTrue(InterMedNetworkBridge.hasClientReceiver(channel));

        Object payload = new Object();
        InterMedNetworkBridge.dispatchClientReceiver(channel, new Object(), new Object(), payload, new Object());

        assertSame(payload, receivedPayload.get());
    }

    @Test
    void registersAndDispatchesServerReceivers() {
        ResourceLocation channel = new ResourceLocation("demo", "server_sync");
        AtomicReference<Object> receivedPayload = new AtomicReference<>();

        assertTrue(InterMedNetworkBridge.registerServerReceiver(channel,
            (server, player, handler, buf, responseSender) -> receivedPayload.set(buf)));
        assertTrue(InterMedNetworkBridge.hasServerReceiver(channel));

        Object payload = new Object();
        InterMedNetworkBridge.dispatchServerReceiver(channel, new Object(), new Object(), new Object(), payload, new Object());

        assertSame(payload, receivedPayload.get());
    }

    @Test
    void assignsDeterministicNumericChannelIdsPerDirection() {
        ResourceLocation channel = new ResourceLocation("demo", "sync");

        long clientboundId = InterMedNetworkBridge.resolveChannelNumericId(channel, InterMedNetworkBridge.DeliveryDirection.CLIENTBOUND);
        long serverboundId = InterMedNetworkBridge.resolveChannelNumericId(channel, InterMedNetworkBridge.DeliveryDirection.SERVERBOUND);

        assertEquals(clientboundId,
            InterMedNetworkBridge.resolveChannelNumericId(channel, InterMedNetworkBridge.DeliveryDirection.CLIENTBOUND));
        assertNotEquals(clientboundId, serverboundId);
        assertTrue(InterMedNetworkBridge.findChannel(InterMedNetworkBridge.DeliveryDirection.CLIENTBOUND, clientboundId).isPresent());
    }

    @Test
    void encodesAndDispatchesTypedClientboundEnvelopes() {
        ResourceLocation channel = new ResourceLocation("demo", "typed_client");
        AtomicReference<String> receivedPayload = new AtomicReference<>();

        assertTrue(InterMedNetworkBridge.registerTypedClientReceiver(
            channel,
            "demo",
            InterMedNetworkBridge.PayloadCodec.utf8String(),
            (client, handler, payload, responseSender) -> receivedPayload.set(payload)
        ));

        byte[] frame = InterMedNetworkBridge.encodeClientbound(
            channel,
            "demo",
            "bridge-payload",
            InterMedNetworkBridge.PayloadCodec.utf8String()
        );

        assertTrue(InterMedNetworkBridge.dispatchClientEnvelope(frame, new Object(), new Object(), new Object()));
        assertEquals("bridge-payload", receivedPayload.get());
    }

    @Test
    void encodesAndDispatchesTypedServerboundEnvelopes() {
        ResourceLocation channel = new ResourceLocation("demo", "typed_server");
        AtomicReference<String> receivedPayload = new AtomicReference<>();

        assertTrue(InterMedNetworkBridge.registerTypedServerReceiver(
            channel,
            "demo",
            InterMedNetworkBridge.PayloadCodec.utf8String(),
            (server, player, handler, payload, responseSender) -> receivedPayload.set(payload)
        ));

        byte[] frame = InterMedNetworkBridge.encodeServerbound(
            channel,
            "demo",
            "server-bridge-payload",
            InterMedNetworkBridge.PayloadCodec.utf8String()
        );

        assertTrue(InterMedNetworkBridge.dispatchServerEnvelope(frame, new Object(), new Object(), new Object(), new Object()));
        assertEquals("server-bridge-payload", receivedPayload.get());
    }

    @Test
    void fallsBackToLogicalChannelWhenNumericIdDiffers() {
        ResourceLocation channel = new ResourceLocation("demo", "fallback");
        AtomicReference<String> receivedPayload = new AtomicReference<>();

        assertTrue(InterMedNetworkBridge.registerTypedClientReceiver(
            channel,
            "demo",
            InterMedNetworkBridge.PayloadCodec.utf8String(),
            (client, handler, payload, responseSender) -> receivedPayload.set(payload)
        ));

        InterMedNetworkBridge.InterMedPacketEnvelope envelope = new InterMedNetworkBridge.InterMedPacketEnvelope(
            InterMedNetworkBridge.protocolVersion(),
            InterMedNetworkBridge.DeliveryDirection.CLIENTBOUND,
            42L,
            "demo",
            channel,
            InterMedNetworkBridge.PayloadCodec.utf8String().encode("fallback-payload")
        );

        assertTrue(InterMedNetworkBridge.dispatchClientEnvelope(envelope, new Object(), new Object(), new Object()));
        assertEquals("fallback-payload", receivedPayload.get());
        assertEquals(1L, InterMedNetworkBridge.diagnostics().fallbackChannelMatches());
    }

    @Test
    void rejectsUnknownEnvelopeChannels() {
        ResourceLocation channel = new ResourceLocation("ghost", "missing");

        InterMedNetworkBridge.InterMedPacketEnvelope envelope = new InterMedNetworkBridge.InterMedPacketEnvelope(
            InterMedNetworkBridge.protocolVersion(),
            InterMedNetworkBridge.DeliveryDirection.CLIENTBOUND,
            999L,
            "ghost",
            channel,
            InterMedNetworkBridge.PayloadCodec.utf8String().encode("missing")
        );

        assertFalse(InterMedNetworkBridge.dispatchClientEnvelope(envelope, new Object(), new Object(), new Object()));
        assertEquals(1L, InterMedNetworkBridge.diagnostics().droppedEnvelopes());
    }

    @Test
    void exposesDiagnosticsAndChannelDescriptors() {
        ResourceLocation clientChannel = new ResourceLocation("demo", "report_client");
        ResourceLocation serverChannel = new ResourceLocation("demo", "report_server");

        assertTrue(InterMedNetworkBridge.registerClientReceiver(clientChannel, (client, handler, buf, responseSender) -> {}));
        assertTrue(InterMedNetworkBridge.registerTypedServerReceiver(
            serverChannel,
            "demo",
            InterMedNetworkBridge.PayloadCodec.utf8String(),
            (server, player, handler, payload, responseSender) -> {}
        ));

        List<InterMedNetworkBridge.ChannelDescriptor> descriptors = InterMedNetworkBridge.snapshotChannelDescriptors();
        InterMedNetworkBridge.NetworkBridgeDiagnostics diagnostics = InterMedNetworkBridge.diagnostics();

        assertEquals(2, descriptors.size());
        assertEquals(2, diagnostics.registeredChannels());
        assertEquals(new ResourceLocation("intermed", "network_bridge"), diagnostics.transportChannel());
        assertTrue(descriptors.stream().anyMatch(descriptor ->
            descriptor.channelId().equals(serverChannel) && descriptor.typedPayload()));
    }

    @Test
    void completesRegistrySyncHandshakeAcrossJoinAndDisconnect() {
        byte[] serverSnapshot = RegistryTranslationMatrix.serialiseSnapshot(List.of(
            new RegistryTranslationMatrix.SlotEntry(0, "demo:a"),
            new RegistryTranslationMatrix.SlotEntry(1, "demo:b"),
            new RegistryTranslationMatrix.SlotEntry(2, "demo:c")
        ));
        byte[] clientSnapshot = RegistryTranslationMatrix.serialiseSnapshot(List.of(
            new RegistryTranslationMatrix.SlotEntry(1, "demo:a"),
            new RegistryTranslationMatrix.SlotEntry(2, "demo:b"),
            new RegistryTranslationMatrix.SlotEntry(0, "demo:c")
        ));

        List<byte[]> serverToClientFrames = new ArrayList<>();
        List<byte[]> clientToServerFrames = new ArrayList<>();
        Object server = new Object();
        Object player = new Object();
        Object client = new Object();

        InterMedNetworkBridge.registerRegistrySyncChannel(() -> serverSnapshot, () -> clientSnapshot);

        InterMedNetworkBridge.FrameSender clientReplySender = frame -> {
            clientToServerFrames.add(frame);
            assertTrue(InterMedNetworkBridge.dispatchServerEnvelope(frame, server, player, new Object(), new Object()));
        };
        InterMedNetworkBridge.ChannelFrameSender joinSender = (transportChannel, frame) -> {
            assertEquals(InterMedNetworkBridge.transportChannel(), transportChannel);
            serverToClientFrames.add(frame);
            assertTrue(InterMedNetworkBridge.dispatchClientEnvelope(frame, client, new Object(), clientReplySender));
        };

        ServerLifecycleEvents.SERVER_STARTED.invoker().onServerStarted(server);
        ServerPlayConnectionEvents.JOIN.invoker().onPlayReady(player, joinSender, server);

        RegistryTranslationMatrix matrix = RegistryTranslationMatrix.active();
        assertEquals(1, serverToClientFrames.size(), "Server should push exactly one registry snapshot on join");
        assertEquals(1, clientToServerFrames.size(), "Client should acknowledge with exactly one local snapshot");
        assertEquals(1, matrix.serverToClient(0));
        assertEquals(2, matrix.serverToClient(1));
        assertEquals(0, matrix.serverToClient(2));
        assertEquals(0, matrix.clientToServer(1));
        assertEquals(1, matrix.clientToServer(2));
        assertEquals(2, matrix.clientToServer(0));

        ServerPlayConnectionEvents.DISCONNECT.invoker().onPlayDisconnect(player, server);
        assertEquals(0, RegistryTranslationMatrix.active().serverSize());
        assertEquals(0, RegistryTranslationMatrix.active().clientSize());
    }
}
