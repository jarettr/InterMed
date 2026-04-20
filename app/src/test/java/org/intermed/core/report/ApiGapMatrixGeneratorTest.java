package org.intermed.core.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiGapMatrixGeneratorTest {

    @Test
    void reportsPresentAndMissingBridgeSurface() {
        JsonObject report = ApiGapMatrixGenerator.generate();
        JsonObject summary = report.getAsJsonObject("summary");
        JsonObject byEcosystem = summary.getAsJsonObject("byEcosystem");

        assertEquals("intermed-api-gap-matrix-v1", report.get("schema").getAsString());
        assertTrue(summary.get("total").getAsInt() > 40);
        assertTrue(summary.get("present").getAsInt() > 20);
        assertEquals(0, summary.get("missing").getAsInt());
        assertNotNull(byEcosystem.getAsJsonObject("Fabric"));
        assertNotNull(byEcosystem.getAsJsonObject("Forge"));
        assertNotNull(byEcosystem.getAsJsonObject("NeoForge"));

        JsonObject modInitializer = symbol(report, "net.fabricmc.api.ModInitializer", "onInitialize");
        assertEquals("present", modInitializer.get("status").getAsString());
        assertEquals("alpha", modInitializer.get("stage").getAsString());
        assertEquals("P0", modInitializer.get("priority").getAsString());

        JsonObject commandCallback = symbol(
            report,
            "net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback",
            null
        );
        assertEquals("present", commandCallback.get("status").getAsString());
        assertEquals("beta", commandCallback.get("stage").getAsString());
    }

    @Test
    void writesApiGapMatrixJson() throws Exception {
        Path output = Files.createTempFile("intermed-api-gap-matrix", ".json");

        ApiGapMatrixGenerator.writeReport(output);

        JsonObject written = JsonParser.parseString(Files.readString(output, StandardCharsets.UTF_8))
            .getAsJsonObject();
        assertEquals("intermed-api-gap-matrix-v1", written.get("schema").getAsString());
        assertTrue(written.getAsJsonArray("symbols").size() > 0);
    }

    private static JsonObject symbol(JsonObject report, String owner, String member) {
        JsonArray symbols = report.getAsJsonArray("symbols");
        for (var element : symbols) {
            JsonObject symbol = element.getAsJsonObject();
            boolean ownerMatches = owner.equals(symbol.get("owner").getAsString());
            boolean memberMatches = member == null
                ? !symbol.has("member")
                : symbol.has("member") && member.equals(symbol.get("member").getAsString());
            if (ownerMatches && memberMatches) {
                return symbol;
            }
        }
        throw new AssertionError("Expected symbol not found: " + owner + "#" + member);
    }
}
