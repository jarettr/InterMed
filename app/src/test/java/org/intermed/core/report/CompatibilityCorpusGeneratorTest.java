package org.intermed.core.report;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilityCorpusGeneratorTest {

    @Test
    void capturesParsedAndUnsupportedCorpusCandidates() throws Exception {
        Path root = Files.createTempDirectory("intermed-compat-corpus");
        Path fabricJar = createFabricJar(root.resolve("alpha-fabric.jar"));
        Path unsupportedJar = createUnsupportedJar(root.resolve("library-only.jar"));
        Path dataPackZip = createDataPackZip(root.resolve("Terralith_1.20_v2.5.4.zip"));

        JsonObject report = CompatibilityCorpusGenerator.generate(List.of(
            unsupportedJar.toFile(),
            fabricJar.toFile(),
            dataPackZip.toFile()
        ));
        JsonObject summary = report.getAsJsonObject("summary");

        assertEquals("intermed-compatibility-corpus-v1", report.get("schema").getAsString());
        assertEquals("manifest-only", report.getAsJsonObject("scope").get("evidenceLevel").getAsString());
        assertEquals(3, summary.get("total").getAsInt());
        assertEquals(2, summary.get("parsed").getAsInt());
        assertEquals(1, summary.get("unsupported").getAsInt());
        assertEquals(3, summary.get("notRun").getAsInt());
        assertEquals(1, summary.getAsJsonObject("byPlatform").get("FABRIC").getAsInt());
        assertEquals(1, summary.getAsJsonObject("byPlatform").get("DATA_PACK").getAsInt());

        JsonObject fabric = candidate(report, "alpha_mod");
        assertEquals("parsed", fabric.get("status").getAsString());
        assertEquals("FABRIC", fabric.get("platform").getAsString());
        assertEquals("unclassified", fabric.get("expectedOutcome").getAsString());
        assertEquals("not-run", fabric.get("sweepStatus").getAsString());
        assertTrue(arrayContains(fabric.getAsJsonArray("mixins"), "alpha.mixins.json"));
        assertTrue(fabric.getAsJsonObject("dependencies").has("fabricloader"));

        JsonObject unsupported = candidateByFile(report, "library-only.jar");
        assertEquals("unsupported", unsupported.get("status").getAsString());

        JsonObject dataPack = candidate(report, "terralith");
        assertEquals("parsed", dataPack.get("status").getAsString());
        assertEquals("data-pack", dataPack.get("artifactType").getAsString());
        assertEquals("DATA_PACK", dataPack.get("platform").getAsString());
        assertEquals("2.5.4", dataPack.get("version").getAsString());
        assertTrue(dataPack.get("serverData").getAsBoolean());
    }

    @Test
    void writesCompatibilityCorpusJson() throws Exception {
        Path root = Files.createTempDirectory("intermed-compat-corpus-write");
        Path output = root.resolve("corpus.json");
        Path fabricJar = createFabricJar(root.resolve("alpha-fabric.jar"));

        CompatibilityCorpusGenerator.writeReport(output, List.of(fabricJar.toFile()));

        JsonObject written = JsonParser.parseString(Files.readString(output, StandardCharsets.UTF_8))
            .getAsJsonObject();
        assertEquals("intermed-compatibility-corpus-v1", written.get("schema").getAsString());
        assertEquals(1, written.getAsJsonArray("candidates").size());
    }

    private static JsonObject candidate(JsonObject report, String modId) {
        JsonArray candidates = report.getAsJsonArray("candidates");
        for (var element : candidates) {
            JsonObject candidate = element.getAsJsonObject();
            if (candidate.has("id") && modId.equals(candidate.get("id").getAsString())) {
                return candidate;
            }
        }
        throw new AssertionError("Expected candidate not found: " + modId);
    }

    private static JsonObject candidateByFile(JsonObject report, String file) {
        JsonArray candidates = report.getAsJsonArray("candidates");
        for (var element : candidates) {
            JsonObject candidate = element.getAsJsonObject();
            if (file.equals(candidate.get("file").getAsString())) {
                return candidate;
            }
        }
        throw new AssertionError("Expected candidate file not found: " + file);
    }

    private static boolean arrayContains(JsonArray array, String value) {
        for (var element : array) {
            if (value.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static Path createFabricJar(Path jarPath) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "alpha_mod",
                  "name": "Alpha Mod",
                  "version": "1.2.3",
                  "depends": {
                    "fabricloader": ">=0.15.0"
                  },
                  "entrypoints": {
                    "main": ["demo.alpha.Main"],
                    "client": ["demo.alpha.Client"]
                  },
                  "mixins": ["alpha.mixins.json"]
                }
                """.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jarPath;
    }

    private static Path createUnsupportedJar(Path jarPath) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            output.putNextEntry(new JarEntry("META-INF/placeholder.txt"));
            output.write("not a mod".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jarPath;
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

            output.putNextEntry(new JarEntry("data/terralith/worldgen/noise_settings/test.json"));
            output.write("{}".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return zipPath;
    }
}
