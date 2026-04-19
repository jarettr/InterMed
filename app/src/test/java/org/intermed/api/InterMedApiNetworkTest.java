package org.intermed.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.intermed.core.bridge.ForgeNetworkBridge;
import org.intermed.core.bridge.InterMedNetworkBridge;
import org.intermed.core.bridge.NeoForgeNetworkBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InterMedApiNetworkTest {

    @AfterEach
    void tearDown() {
        ForgeNetworkBridge.resetForTests();
        NeoForgeNetworkBridge.resetForTests();
        InterMedNetworkBridge.resetForTests();
    }

    @Test
    void exposesNetworkDiagnosticsAndEnvelopeHelpers() {
        ResourceLocation channel = new ResourceLocation("demo", "api");
        InterMedNetworkBridge.registerClientReceiver(channel, (client, handler, buf, responseSender) -> {});
        ForgeNetworkBridge.channel(new ResourceLocation("demo", "forge_api"), "1")
            .registerClientboundUtf8(0, "demo", (client, handler, payload, responseSender) -> {});
        NeoForgeNetworkBridge.payload(new ResourceLocation("demo", "neo_api"), "1", "demo")
            .registerClientboundUtf8((client, handler, payload, responseSender) -> {});

        assertEquals(InterMedNetworkBridge.protocolVersion(), InterMedAPI.networkProtocolVersion());
        assertEquals(InterMedNetworkBridge.transportChannel().toString(), InterMedAPI.networkTransportChannel());
        assertEquals(InterMedNetworkBridge.diagnostics().registeredChannels(), InterMedAPI.networkRegisteredChannelCount());
        assertEquals(
            InterMedNetworkBridge.resolveChannelNumericId(channel, InterMedNetworkBridge.DeliveryDirection.CLIENTBOUND),
            InterMedAPI.networkClientboundChannelId("demo:api")
        );

        JsonObject diagnostics = JsonParser.parseString(InterMedAPI.networkDiagnosticsJson()).getAsJsonObject();
        assertEquals("intermed:network_bridge", diagnostics.get("transportChannel").getAsString());
        assertTrue(diagnostics.get("registeredChannels").getAsInt() >= 1);
        assertTrue(diagnostics.has("forgeSimpleImpl"));
        assertTrue(diagnostics.has("neoForgePayloadBridge"));

        String envelope = InterMedAPI.networkCreateClientboundUtf8Envelope("demo:api", "payload");
        JsonObject summary = JsonParser.parseString(InterMedAPI.networkDecodeEnvelopeSummary(envelope)).getAsJsonObject();
        assertEquals("ok", summary.get("status").getAsString());
        assertEquals("CLIENTBOUND", summary.get("direction").getAsString());
        assertEquals("demo:api", summary.get("logicalChannel").getAsString());
        assertTrue(summary.get("payloadBytes").getAsInt() > 0);
    }

    @Test
    void reportsInvalidEnvelopeSummariesWithoutThrowing() {
        JsonObject summary = JsonParser.parseString(InterMedAPI.networkDecodeEnvelopeSummary("not-base64")).getAsJsonObject();
        assertEquals("invalid", summary.get("status").getAsString());
        assertTrue(summary.has("error"));
    }
}
