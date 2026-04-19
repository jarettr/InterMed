package org.intermed.core.bridge;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeNetworkBridgeTest {

    @AfterEach
    void tearDown() {
        NeoForgeNetworkBridge.resetForTests();
        InterMedNetworkBridge.resetForTests();
        FMLJavaModLoadingContext.reset();
    }

    @Test
    void registersPayloadsAndRoutesFramesThroughUniversalBridge() {
        ResourceLocation payloadId = new ResourceLocation("demo", "neo_payload");
        NeoForgeNetworkBridge.PayloadHandle payload = NeoForgeNetworkBridge.payload(payloadId, "1", "demo");
        AtomicReference<String> clientPayload = new AtomicReference<>();
        AtomicReference<String> serverPayload = new AtomicReference<>();

        payload.registerBidirectionalUtf8(
            (client, handler, body, responseSender) -> clientPayload.set(body),
            (server, player, handler, body, responseSender) -> serverPayload.set(body)
        );

        byte[] clientFrame = payload.createClientboundUtf8Frame("neo-client");
        byte[] serverFrame = payload.createServerboundUtf8Frame("neo-server");

        assertTrue(InterMedNetworkBridge.dispatchClientEnvelope(clientFrame, new Object(), new Object(), new Object()));
        assertTrue(InterMedNetworkBridge.dispatchServerEnvelope(serverFrame, new Object(), new Object(), new Object(), new Object()));
        assertEquals("neo-client", clientPayload.get());
        assertEquals("neo-server", serverPayload.get());
        assertEquals(1, NeoForgeNetworkBridge.snapshotPayloads().size());
        assertEquals(2L, NeoForgeNetworkBridge.diagnostics().encodedEnvelopes());
    }

    @Test
    void registerIfAvailableAttachesRuntimeListenersAndObservesEvents() {
        assertTrue(NeoForgeNetworkBridge.registerIfAvailable());
        assertEquals(2, FMLJavaModLoadingContext.get().getModEventBus().listenerCount());

        FMLJavaModLoadingContext.get().getModEventBus().dispatch(new RegisterPayloadHandlersEvent());
        FMLJavaModLoadingContext.get().getModEventBus().dispatch(new RegisterPayloadHandlerEvent());

        NeoForgeNetworkBridge.NeoForgeNetworkDiagnostics diagnostics = NeoForgeNetworkBridge.diagnostics();
        assertTrue(diagnostics.listenersRegistered());
        assertEquals(2L, diagnostics.observedRegistrationEvents());
        assertTrue(diagnostics.observedEventClass().contains("RegisterPayloadHandlerEvent"));
        assertEquals("string-registrar", diagnostics.observedRegistrarStyle());
    }

    @Test
    void sourceFileContainsNoDirectNeoForgeImports() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Path source = root.resolve("src/main/java/org/intermed/core/bridge/NeoForgeNetworkBridge.java");
        if (!Files.exists(source)) {
            source = root.resolve("app/src/main/java/org/intermed/core/bridge/NeoForgeNetworkBridge.java");
        }

        String text = Files.readString(source);
        assertFalse(text.contains("import net.neoforged."),
            "Forge-only compilation must not depend on direct NeoForge network imports");
    }
}
