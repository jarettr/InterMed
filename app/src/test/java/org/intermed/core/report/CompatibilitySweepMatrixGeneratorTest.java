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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompatibilitySweepMatrixGeneratorTest {

    @Test
    void linksCorpusCandidatesToHarnessResults() throws Exception {
        Path root = Files.createTempDirectory("intermed-sweep-matrix");
        Path fabricJar = createFabricJar(root.resolve("sample-mod.jar"));
        Path unsupportedJar = createUnsupportedJar(root.resolve("library-only.jar"));
        JsonObject corpus = CompatibilityCorpusGenerator.generate(List.of(
            fabricJar.toFile(),
            unsupportedJar.toFile()
        ));
        JsonObject harnessResults = harnessResults();

        JsonObject matrix = CompatibilitySweepMatrixGenerator.generate(corpus, harnessResults);
        JsonObject summary = matrix.getAsJsonObject("summary");

        assertEquals("intermed-compatibility-sweep-matrix-v1", matrix.get("schema").getAsString());
        assertEquals(
            "harness-result-normalization",
            matrix.getAsJsonObject("scope").get("evidenceLevel").getAsString()
        );
        assertEquals(2, summary.get("corpusTotal").getAsInt());
        assertEquals(1, summary.get("linkedCandidates").getAsInt());
        assertEquals(1, summary.get("untestedCandidates").getAsInt());
        assertEquals(2, summary.get("resultsTotal").getAsInt());
        assertEquals(1, summary.get("passCount").getAsInt());
        assertEquals(1, summary.get("failCount").getAsInt());
        assertEquals(1, summary.get("unmatchedResults").getAsInt());

        JsonObject sample = candidate(matrix, "sample_mod");
        assertEquals("passed", sample.get("sweepStatus").getAsString());
        assertEquals("PASS_BRIDGED", sample.get("bestOutcome").getAsString());
        assertFalse(sample.get("fieldTested").getAsBoolean());
        assertEquals(1, sample.getAsJsonArray("matchedResults").size());

        JsonObject unsupported = candidateByFile(matrix, "library-only.jar");
        assertEquals("not-run", unsupported.get("sweepStatus").getAsString());
        assertEquals(1, matrix.getAsJsonArray("unmatchedResults").size());
    }

    @Test
    void writesSweepMatrixJsonFromCorpusAndResultPaths() throws Exception {
        Path root = Files.createTempDirectory("intermed-sweep-matrix-write");
        Path fabricJar = createFabricJar(root.resolve("sample-mod.jar"));
        Path corpusPath = root.resolve("corpus.json");
        Path resultsPath = root.resolve("results.json");
        Path output = root.resolve("matrix.json");

        CompatibilityCorpusGenerator.writeReport(corpusPath, List.of(fabricJar.toFile()));
        Files.writeString(resultsPath, harnessResults().toString(), StandardCharsets.UTF_8);

        CompatibilitySweepMatrixGenerator.writeReport(output, corpusPath, resultsPath);

        JsonObject written = JsonParser.parseString(Files.readString(output, StandardCharsets.UTF_8))
            .getAsJsonObject();
        assertEquals("intermed-compatibility-sweep-matrix-v1", written.get("schema").getAsString());
        assertEquals(1, written.getAsJsonObject("summary").get("linkedCandidates").getAsInt());
    }

    @Test
    void linksHarnessAliasesWithoutCrossLoaderOrWrongVersionMatches() {
        JsonObject corpus = new JsonObject();
        JsonArray candidates = new JsonArray();
        candidates.add(candidate("architectury-9.2.14-forge.jar", "architectury", "Architectury", "9.2.14", "FORGE", "parsed"));
        candidates.add(candidate("architectury-9.2.14-fabric.jar", "architectury", "Architectury", "9.2.14", "FABRIC", "parsed"));
        candidates.add(candidate("architectury-9.1.12-forge.jar", "architectury", "Architectury", "9.1.12", "FORGE", "parsed"));
        candidates.add(candidate("collective-1.20.1-8.19.jar", "collective", "Collective", "8.19", "FABRIC", "parsed"));
        candidates.add(candidate("Terralith_1.20_v2.5.4.zip", "terralith", "Terralith", "2.5.4", "DATA_PACK", "parsed"));
        candidates.add(candidate("voicechat-bukkit-2.6.16.jar", null, null, null, null, "unsupported"));
        candidates.add(candidate("tacz-1.20.1-1.1.7-hotfix2.jar", "tacz", "Timeless & Classics Guns: Zero", "${file.jarVersion}", "FORGE", "parsed"));
        corpus.add("candidates", candidates);

        JsonObject harnessResults = new JsonObject();
        JsonArray results = new JsonArray();
        results.add(result(
            "single-architectury-api-forge",
            "architectury-api",
            "Architectury API",
            "9.2.14+forge",
            "FORGE",
            "PASS",
            true
        ));
        results.add(result(
            "single-collective-forge",
            "collective",
            "Collective",
            "1.20.1-8.19-fabric+forge+neo",
            "FORGE",
            "PASS",
            true
        ));
        results.add(result(
            "single-simple-voice-chat-fabric",
            "simple-voice-chat",
            "Simple Voice Chat",
            "bukkit-2.6.16",
            "FABRIC",
            "PASS",
            true
        ));
        results.add(result(
            "single-timeless-and-classics-zero-forge",
            "timeless-and-classics-zero",
            "[TaCZ] Timeless and Classics Zero",
            "1.1.7-hotfix2",
            "FORGE",
            "PASS",
            true
        ));
        results.add(result(
            "single-terralith-fabric",
            "terralith",
            "Terralith",
            "2.5.4",
            "FABRIC",
            "PASS",
            true
        ));
        harnessResults.add("results", results);

        JsonObject matrix = CompatibilitySweepMatrixGenerator.generate(corpus, harnessResults);

        assertEquals("passed", candidateByFile(matrix, "architectury-9.2.14-forge.jar").get("sweepStatus").getAsString());
        assertEquals("not-run", candidateByFile(matrix, "architectury-9.2.14-fabric.jar").get("sweepStatus").getAsString());
        assertEquals("not-run", candidateByFile(matrix, "architectury-9.1.12-forge.jar").get("sweepStatus").getAsString());
        assertEquals("passed", candidateByFile(matrix, "collective-1.20.1-8.19.jar").get("sweepStatus").getAsString());
        assertEquals("passed", candidateByFile(matrix, "Terralith_1.20_v2.5.4.zip").get("sweepStatus").getAsString());
        assertEquals("passed", candidateByFile(matrix, "voicechat-bukkit-2.6.16.jar").get("sweepStatus").getAsString());
        assertEquals("passed", candidateByFile(matrix, "tacz-1.20.1-1.1.7-hotfix2.jar").get("sweepStatus").getAsString());
        assertEquals(0, matrix.getAsJsonObject("summary").get("unmatchedResults").getAsInt());
    }

    private static JsonObject harnessResults() {
        JsonObject root = new JsonObject();
        root.addProperty("generatedAt", "2026-04-19T00:00:00Z");
        root.addProperty("totalCount", 2);
        JsonArray results = new JsonArray();
        results.add(result("single-sample-mod-fabric", "sample_mod", "Sample Mod", "PASS_BRIDGED", true));
        results.add(result("single-other-mod-forge", "other_mod", "Other Mod", "FAIL_CRASH", false));
        root.add("results", results);
        return root;
    }

    private static JsonObject result(String id, String slug, String name, String outcome, boolean passed) {
        return result(id, slug, name, "1.0.0", id.endsWith("forge") ? "FORGE" : "FABRIC", outcome, passed);
    }

    private static JsonObject result(String id,
                                     String slug,
                                     String name,
                                     String version,
                                     String loader,
                                     String outcome,
                                     boolean passed) {
        JsonObject result = new JsonObject();
        result.addProperty("id", id);
        result.addProperty("description", "Single: " + slug);
        result.addProperty("loader", loader);
        result.addProperty("modCount", 1);
        result.addProperty("outcome", outcome);
        result.addProperty("passed", passed);
        result.addProperty("startupMs", passed ? 12345 : 0);
        result.addProperty("exitCode", passed ? 0 : 1);
        result.addProperty("executedAt", "2026-04-19T00:00:01Z");
        JsonArray mods = new JsonArray();
        JsonObject mod = new JsonObject();
        mod.addProperty("slug", slug);
        mod.addProperty("name", name);
        mod.addProperty("version", version);
        mod.addProperty("downloads", 42);
        mod.addProperty("modrinthUrl", "https://modrinth.com/mod/" + slug);
        mods.add(mod);
        result.add("mods", mods);
        result.add("issues", new JsonArray());
        return result;
    }

    private static JsonObject candidate(String file,
                                        String id,
                                        String name,
                                        String version,
                                        String platform,
                                        String status) {
        JsonObject candidate = new JsonObject();
        candidate.addProperty("file", file);
        if (id != null) {
            candidate.addProperty("id", id);
        }
        if (name != null) {
            candidate.addProperty("name", name);
        }
        if (version != null) {
            candidate.addProperty("version", version);
        }
        if (platform != null) {
            candidate.addProperty("platform", platform);
        }
        candidate.addProperty("status", status);
        return candidate;
    }

    private static JsonObject candidate(JsonObject matrix, String modId) {
        JsonArray candidates = matrix.getAsJsonArray("candidates");
        for (var element : candidates) {
            JsonObject candidate = element.getAsJsonObject();
            if (candidate.has("id") && modId.equals(candidate.get("id").getAsString())) {
                return candidate;
            }
        }
        throw new AssertionError("Expected candidate not found: " + modId);
    }

    private static JsonObject candidateByFile(JsonObject matrix, String file) {
        JsonArray candidates = matrix.getAsJsonArray("candidates");
        for (var element : candidates) {
            JsonObject candidate = element.getAsJsonObject();
            if (file.equals(candidate.get("file").getAsString())) {
                return candidate;
            }
        }
        throw new AssertionError("Expected candidate file not found: " + file);
    }

    private static Path createFabricJar(Path jarPath) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write("""
                {
                  "id": "sample_mod",
                  "name": "Sample Mod",
                  "version": "1.0.0",
                  "entrypoints": { "main": ["demo.Sample"] }
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
}
