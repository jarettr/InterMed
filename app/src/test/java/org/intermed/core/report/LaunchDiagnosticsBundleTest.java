package org.intermed.core.report;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.core.config.RuntimeConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchDiagnosticsBundleTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("runtime.game.dir");
        System.clearProperty("runtime.mods.dir");
        System.clearProperty("intermed.modsDir");
        RuntimeConfig.resetForTests();
    }

    @Test
    void writesSelfContainedAlphaTriageBundle() throws Exception {
        Path root = Files.createTempDirectory("intermed-diagnostics-bundle");
        Path gameDir = root.resolve("game");
        Path modsDir = gameDir.resolve("intermed_mods");
        Path configDir = gameDir.resolve("config");
        Files.createDirectories(modsDir);
        Files.createDirectories(configDir);
        Files.createDirectories(gameDir.resolve("logs"));
        Files.createDirectories(gameDir.resolve(".intermed/vfs"));

        Files.writeString(gameDir.resolve("logs/latest.log"), "synthetic launch log", StandardCharsets.UTF_8);
        Files.writeString(gameDir.resolve(".intermed/vfs/diagnostics.json"), "{\"conflicts\":[]}", StandardCharsets.UTF_8);
        Files.writeString(configDir.resolve("intermed-security-profiles.json"), """
            [{"modId":"bundle_mod","capabilities":["FILE_READ"]}]
            """, StandardCharsets.UTF_8);

        Path dependencyJar = createFabricJar(modsDir.resolve("dep-mod.jar"), "dep_mod", List.of());
        Path modJar = createFabricJar(modsDir.resolve("bundle-mod.jar"), "bundle_mod", List.of("dep_mod"));
        Path output = root.resolve("bundle.zip");

        LaunchDiagnosticsBundle.BundleResult result = LaunchDiagnosticsBundle.writeBundle(
            output,
            List.of(dependencyJar.toFile(), modJar.toFile()),
            gameDir,
            modsDir,
            configDir
        );

        assertTrue(Files.isRegularFile(result.archive()));
        assertTrue(result.entries().contains("manifest.json"));
        assertTrue(result.entries().contains("reports/compatibility-report.json"));
        assertTrue(result.entries().contains("reports/compatibility-corpus.json"));
        assertTrue(result.entries().contains("reports/compatibility-sweep-matrix.json"));
        assertTrue(result.entries().contains("reports/sbom.cdx.json"));
        assertTrue(result.entries().contains("reports/api-gap-matrix.json"));
        assertTrue(result.entries().contains("reports/dependency-plan.json"));
        assertTrue(result.entries().contains("reports/security-report.json"));
        assertTrue(result.entries().contains("reports/runtime-config.json"));
        assertTrue(result.entries().contains("reports/launch-readiness-report.json"));
        assertTrue(result.entries().contains("artifacts/logs/latest.log"));
        assertTrue(result.entries().contains("artifacts/vfs/diagnostics.json"));
        assertTrue(result.entries().contains("artifacts/security/intermed-security-profiles.json"));

        try (ZipFile zip = new ZipFile(output.toFile())) {
            Set<String> names = zip.stream()
                .map(entry -> entry.getName())
                .collect(java.util.stream.Collectors.toSet());
            assertTrue(names.containsAll(result.entries()));

            JsonObject manifest = readJson(zip, "manifest.json");
            JsonObject readiness = manifest.getAsJsonObject("launchReadiness");
            assertEquals("intermed-launch-diagnostics-bundle-v1", manifest.get("schema").getAsString());
            assertEquals(2, manifest.get("modJarCount").getAsInt());
            assertFalse(readiness.get("compatibilityLaneIsSecurityProof").getAsBoolean());
            assertFalse(readiness.get("fieldTested").getAsBoolean());

            JsonObject dependencyPlan = readJson(zip, "reports/dependency-plan.json");
            assertEquals("resolved", dependencyPlan.get("status").getAsString());
            assertTrue(dependencyPlan.getAsJsonObject("resolvedVersions").has("bundle_mod"));
            assertTrue(arrayContains(
                dependencyPlan.getAsJsonObject("dependencyEdges").getAsJsonArray("bundle_mod"),
                "dep_mod"
            ));

            JsonObject apiGapMatrix = readJson(zip, "reports/api-gap-matrix.json");
            assertEquals("intermed-api-gap-matrix-v1", apiGapMatrix.get("schema").getAsString());
            assertTrue(apiGapMatrix.getAsJsonObject("summary").get("total").getAsInt() > 0);

            JsonObject compatibilityCorpus = readJson(zip, "reports/compatibility-corpus.json");
            assertEquals("intermed-compatibility-corpus-v1", compatibilityCorpus.get("schema").getAsString());
            assertEquals(2, compatibilityCorpus.getAsJsonObject("summary").get("total").getAsInt());

            JsonObject sweepMatrix = readJson(zip, "reports/compatibility-sweep-matrix.json");
            assertEquals("intermed-compatibility-sweep-matrix-v1", sweepMatrix.get("schema").getAsString());
            assertEquals(
                "corpus-only-not-run",
                sweepMatrix.getAsJsonObject("scope").get("evidenceLevel").getAsString()
            );

            JsonObject readinessReport = readJson(zip, "reports/launch-readiness-report.json");
            assertEquals("intermed-launch-readiness-report-v1", readinessReport.get("schema").getAsString());

            JsonObject securityReport = readJson(zip, "reports/security-report.json");
            assertTrue(securityReport.get("strictMode").getAsBoolean());
            assertTrue(securityReport.get("externalProfilesPresent").getAsBoolean());
            assertTrue(hasBundleModSecurityEntry(securityReport));
        }
    }

    @Test
    void linksHarnessResultsWhenProvided() throws Exception {
        Path root = Files.createTempDirectory("intermed-diagnostics-bundle-harness");
        Path gameDir = root.resolve("game");
        Path modsDir = gameDir.resolve("intermed_mods");
        Path configDir = gameDir.resolve("config");
        Path harnessResults = root.resolve("results.json");
        Files.createDirectories(modsDir);
        Files.createDirectories(configDir);
        Files.writeString(harnessResults, harnessResultsJson("bundle_mod"), StandardCharsets.UTF_8);

        Path modJar = createFabricJar(modsDir.resolve("bundle-mod.jar"), "bundle_mod", List.of());
        Path output = root.resolve("bundle.zip");

        LaunchDiagnosticsBundle.BundleResult result = LaunchDiagnosticsBundle.writeBundle(
            output,
            List.of(modJar.toFile()),
            gameDir,
            modsDir,
            configDir,
            harnessResults
        );

        assertTrue(result.entries().contains("reports/compatibility-sweep-matrix.json"));
        assertTrue(result.entries().contains("artifacts/harness/results.json"));
        try (ZipFile zip = new ZipFile(output.toFile())) {
            JsonObject sweepMatrix = readJson(zip, "reports/compatibility-sweep-matrix.json");
            assertEquals(
                "harness-result-normalization",
                sweepMatrix.getAsJsonObject("scope").get("evidenceLevel").getAsString()
            );
            assertEquals(1, sweepMatrix.getAsJsonObject("summary").get("linkedCandidates").getAsInt());
            assertEquals(1, sweepMatrix.getAsJsonObject("summary").get("passCount").getAsInt());
        }
    }

    private static boolean arrayContains(com.google.gson.JsonArray array, String expected) {
        for (var element : array) {
            if (expected.equals(element.getAsString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasBundleModSecurityEntry(JsonObject securityReport) {
        for (var element : securityReport.getAsJsonArray("mods")) {
            JsonObject mod = element.getAsJsonObject();
            if ("bundle_mod".equals(mod.get("id").getAsString())
                    && mod.getAsJsonArray("declaredPermissions").size() == 1) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject readJson(ZipFile zip, String entryName) throws Exception {
        var entry = zip.getEntry(entryName);
        assertNotNull(entry);
        try (var input = zip.getInputStream(entry)) {
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        }
    }

    private static Path createFabricJar(Path jarPath, String modId, List<String> depends) throws Exception {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jarPath))) {
            output.putNextEntry(new JarEntry("fabric.mod.json"));
            output.write(fabricManifest(modId, depends).getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return jarPath;
    }

    private static String fabricManifest(String modId, List<String> depends) {
        String dependencyBlock = depends.isEmpty()
            ? ""
            : """
                ,
                  "depends": {
                    %s
                  }
                """.formatted(depends.stream()
                    .map(dep -> "\"" + dep + "\": \"*\"")
                    .collect(java.util.stream.Collectors.joining(",\n    ")));
        return """
            {
              "id": "%s",
              "version": "1.0.0",
              "entrypoints": { "main": ["demo.bundle.EntryPoint"] },
              "intermed:permissions": ["FILE_READ"]%s
            }
            """.formatted(modId, dependencyBlock);
    }

    private static String harnessResultsJson(String modId) {
        return """
            {
              "generatedAt": "2026-04-19T00:00:00Z",
              "totalCount": 1,
              "results": [
                {
                  "id": "single-%s-fabric",
                  "description": "Single: %s",
                  "loader": "FABRIC",
                  "modCount": 1,
                  "outcome": "PASS",
                  "passed": true,
                  "startupMs": 12000,
                  "exitCode": 0,
                  "executedAt": "2026-04-19T00:00:01Z",
                  "mods": [
                    {
                      "slug": "%s",
                      "name": "%s",
                      "version": "1.0.0",
                      "downloads": 1
                    }
                  ],
                  "issues": []
                }
              ]
            }
            """.formatted(modId, modId, modId, modId);
    }
}
