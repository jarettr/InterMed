package org.intermed.core.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import org.intermed.core.bridge.ForgeNetworkBridge;
import org.intermed.core.bridge.InterMedNetworkBridge;
import org.intermed.core.bridge.NeoForgeNetworkBridge;
import org.intermed.core.sandbox.WitContractCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityReportGeneratorTest {

    @AfterEach
    void tearDown() {
        ForgeNetworkBridge.resetForTests();
        NeoForgeNetworkBridge.resetForTests();
        InterMedNetworkBridge.resetForTests();
    }

    @Test
    void includesNetworkBridgeRuntimeSurfaceInReport() {
        InterMedNetworkBridge.registerClientReceiver(new ResourceLocation("demo", "client"), (client, handler, buf, responseSender) -> {});
        InterMedNetworkBridge.registerTypedServerReceiver(
            new ResourceLocation("demo", "server"),
            "demo",
            InterMedNetworkBridge.PayloadCodec.utf8String(),
            (server, player, handler, payload, responseSender) -> {}
        );
        ForgeNetworkBridge.channel(new ResourceLocation("demo", "forge"), "1")
            .registerClientboundUtf8(0, "demo", (client, handler, payload, responseSender) -> {});
        NeoForgeNetworkBridge.payload(new ResourceLocation("demo", "neo"), "1", "demo")
            .registerServerboundUtf8((server, player, handler, payload, responseSender) -> {});

        JsonObject report = CompatibilityReportGenerator.generate(List.<File>of());
        JsonObject runtime = report.getAsJsonObject("runtime");
        JsonObject sandboxes = runtime.getAsJsonObject("sandboxes");
        JsonObject sandboxManager = runtime.getAsJsonObject("sandboxManager");
        JsonObject network = runtime.getAsJsonObject("networkBridge");
        JsonArray channels = network.getAsJsonArray("channels");
        JsonObject forge = network.getAsJsonObject("forgeSimpleImpl");
        JsonObject neoForge = network.getAsJsonObject("neoForgePayloadBridge");

        assertEquals(WitContractCatalog.contractDigest(), sandboxes.get("hostContractDigest").getAsString());
        assertEquals(
            "org.intermed.sandbox.guest.InterMedSandboxHost",
            sandboxes.get("javaBindingsClass").getAsString()
        );
        assertTrue(sandboxManager.get("hostExportCount").getAsInt() >= 1);
        assertEquals("intermed:network_bridge", network.get("transportChannel").getAsString());
        assertTrue(network.get("registeredChannels").getAsInt() >= 2);
        assertTrue(channels.size() >= 2);
        assertEquals(1, forge.get("registeredChannels").getAsInt());
        assertEquals(1, neoForge.get("registeredPayloads").getAsInt());

        boolean hasServerbound = false;
        for (int index = 0; index < channels.size(); index++) {
            JsonObject channel = channels.get(index).getAsJsonObject();
            if ("SERVERBOUND".equals(channel.get("direction").getAsString())) {
                hasServerbound = true;
                break;
            }
        }
        assertTrue(hasServerbound);
    }

    @Test
    void reportsDataDrivenZipArchivesWithoutMarkingThemUnsupported() throws Exception {
        Path root = Files.createTempDirectory("intermed-compat-report-data-driven");
        Path dataPack = createDataPackZip(root.resolve("Terralith_1.20_v2.5.4.zip"));

        JsonObject report = CompatibilityReportGenerator.generate(List.of(dataPack.toFile()));
        JsonObject entry = report.getAsJsonArray("mods").get(0).getAsJsonObject();

        assertEquals("data-driven", entry.get("status").getAsString());
        assertEquals("terralith", entry.get("id").getAsString());
        assertEquals("2.5.4", entry.get("version").getAsString());
        assertEquals("DATA_PACK", entry.get("platform").getAsString());
        assertEquals("data-pack", entry.get("artifactType").getAsString());
        assertTrue(entry.get("serverData").getAsBoolean());
    }

    private static Path createDataPackZip(Path zipPath) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(zipPath))) {
            output.putNextEntry(new JarEntry("pack.mcmeta"));
            output.write("""
                {
                  "pack": {
                    "pack_format": 15,
                    "description": "Terralith"
                  }
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();

            output.putNextEntry(new JarEntry("data/terralith/worldgen/biome/test.json"));
            output.write("{}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return zipPath;
    }
}
