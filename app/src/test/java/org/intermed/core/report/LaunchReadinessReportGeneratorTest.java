package org.intermed.core.report;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchReadinessReportGeneratorTest {

    @Test
    void reportsCompleteAlphaEvidenceWhenArtifactsAndDocsExist() throws Exception {
        Path root = Files.createTempDirectory("intermed-readiness-root");
        Path gameDir = root.resolve("game");
        Path modsDir = gameDir.resolve("intermed_mods");
        Path harnessResults = root.resolve("results.json");
        Files.createDirectories(modsDir);
        createRequiredArtifacts(root);
        createScopeDocs(root);
        createFabricJar(modsDir.resolve("sample-mod.jar"));
        Files.writeString(harnessResults, harnessResultsJson("sample_mod"), StandardCharsets.UTF_8);

        JsonObject report = LaunchReadinessReportGenerator.generate(root, gameDir, modsDir, harnessResults);

        assertEquals("intermed-launch-readiness-report-v1", report.get("schema").getAsString());
        assertTrue(report.getAsJsonObject("summary").get("alphaEvidenceComplete").getAsBoolean());
        assertEquals(0, report.getAsJsonObject("summary").get("missingChecks").getAsInt());
        assertEquals(
            "harness-result-normalization",
            report.getAsJsonObject("compatibility").get("sweepEvidenceLevel").getAsString()
        );
        assertTrue(report.getAsJsonObject("compatibility").get("harnessResultsPresent").getAsBoolean());
        assertEquals("1.20.1", report.getAsJsonObject("scope").get("minecraft").getAsString());
    }

    @Test
    void reportsIncompleteAlphaEvidenceWhenArtifactsAreMissing() throws Exception {
        Path root = Files.createTempDirectory("intermed-readiness-missing");

        JsonObject report = LaunchReadinessReportGenerator.generate(root, null, null, null);

        assertEquals("intermed-launch-readiness-report-v1", report.get("schema").getAsString());
        assertFalse(report.getAsJsonObject("summary").get("alphaEvidenceComplete").getAsBoolean());
        assertTrue(report.getAsJsonObject("summary").get("missingChecks").getAsInt() > 0);
    }

    @Test
    void writesLaunchReadinessJson() throws Exception {
        Path root = Files.createTempDirectory("intermed-readiness-write");
        Path output = root.resolve("readiness.json");

        LaunchReadinessReportGenerator.writeReport(output, root, null, null, null);

        JsonObject written = JsonParser.parseString(Files.readString(output, StandardCharsets.UTF_8))
            .getAsJsonObject();
        assertEquals("intermed-launch-readiness-report-v1", written.get("schema").getAsString());
    }

    private static void createRequiredArtifacts(Path root) throws Exception {
        write(root.resolve("app/build/reports/tests/index.html"), "tests");
        write(root.resolve("app/build/test-results/test/TEST-demo.xml"), "test-results");
        write(root.resolve("app/build/reports/microbench/registry-hot-path.txt"), "microbench");
        write(root.resolve("app/build/reports/soak/runtime-soak.txt"), "soak");
        write(root.resolve("app/build/reports/startup/warm-cache-startup.txt"), "startup");
        write(root.resolve("app/build/reports/observability/observability-evidence.txt"), "observability");
        write(root.resolve("app/build/reports/observability/intermed-metrics.json"), "{\"resourceMetrics\":[]}");
    }

    private static void createScopeDocs(Path root) throws Exception {
        write(root.resolve("README.md"), "InterMed v8.0-alpha-snapshot");
        write(root.resolve("COMPLIANCE.md"), "InterMed v8.0-alpha-snapshot");
        write(root.resolve("LAUNCH_CRITERIA.md"), "InterMed v8.0-alpha-snapshot");
        write(root.resolve("docs/user-guide.md"), "InterMed v8.0-alpha-snapshot");
        write(root.resolve("docs/known-limitations.md"), "InterMed v8.0-alpha-snapshot");
        write(root.resolve("docs/alpha-triage.md"), "InterMed v8.0-alpha-snapshot");
    }

    private static void write(Path path, String value) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, value, StandardCharsets.UTF_8);
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
