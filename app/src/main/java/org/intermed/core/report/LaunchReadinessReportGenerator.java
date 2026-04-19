package org.intermed.core.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.intermed.core.InterMedVersion;
import org.intermed.core.lifecycle.ModDiscovery;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Summarizes alpha launch evidence without executing the gates.
 *
 * <p>This is a presence/guardrail report, not a replacement for Gradle gates or
 * external field validation.</p>
 */
public final class LaunchReadinessReportGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private LaunchReadinessReportGenerator() {}

    public static void writeReport(Path output,
                                   Path projectRoot,
                                   Path gameDir,
                                   Path modsDir,
                                   Path harnessResults) throws Exception {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(
            output,
            GSON.toJson(generate(projectRoot, gameDir, modsDir, harnessResults)),
            StandardCharsets.UTF_8
        );
    }

    public static JsonObject generate(Path projectRoot,
                                      Path gameDir,
                                      Path modsDir,
                                      Path harnessResults) {
        Path root = normalize(projectRoot == null ? Path.of(".") : projectRoot);
        Path game = normalize(gameDir);
        Path mods = normalize(modsDir);
        Path results = normalize(harnessResults);
        List<File> jars = mods == null || !Files.isDirectory(mods)
            ? List.of()
            : ModDiscovery.discoverJars(mods.toFile());

        JsonObject apiGapMatrix = ApiGapMatrixGenerator.generate();
        JsonObject corpus = CompatibilityCorpusGenerator.generate(jars);
        JsonObject sweepMatrix;
        try {
            sweepMatrix = CompatibilitySweepMatrixGenerator.generate(corpus, results);
        } catch (Exception e) {
            sweepMatrix = new JsonObject();
            sweepMatrix.addProperty("schema", "intermed-compatibility-sweep-matrix-v1");
            sweepMatrix.addProperty("status", "failed");
            sweepMatrix.addProperty("error", e.getMessage());
        }

        ArtifactSummary artifactSummary = new ArtifactSummary();
        JsonObject rootJson = new JsonObject();
        rootJson.addProperty("schema", "intermed-launch-readiness-report-v1");
        rootJson.addProperty("intermedVersion", InterMedVersion.BUILD_VERSION);
        rootJson.addProperty("generatedAt", Instant.now().toString());
        rootJson.add("scope", scope());
        rootJson.add("claimGuardrail", claimGuardrail());
        rootJson.add("gateCommands", gateCommands());
        rootJson.add("evidenceArtifacts", evidenceArtifacts(root, artifactSummary));
        rootJson.add("documentationGuardrails", documentationGuardrails(root, artifactSummary));
        rootJson.add("compatibility", compatibility(apiGapMatrix, corpus, sweepMatrix, results));
        rootJson.add("summary", artifactSummary.toJson());
        rootJson.addProperty("gameDir", game == null ? "" : game.toString());
        rootJson.addProperty("modsDir", mods == null ? "" : mods.toString());
        rootJson.addProperty("harnessResults", results == null ? "" : results.toString());
        return rootJson;
    }

    private static JsonObject scope() {
        JsonObject scope = new JsonObject();
        scope.addProperty("releaseLine", "v8.0-alpha-snapshot");
        scope.addProperty("minecraft", "1.20.1");
        scope.addProperty("loaderScope", "Fabric, Forge, NeoForge");
        scope.addProperty("evidenceLevel", "artifact-presence");
        return scope;
    }

    private static JsonObject claimGuardrail() {
        JsonObject guardrail = new JsonObject();
        guardrail.addProperty("productionReady", false);
        guardrail.addProperty("compatibilityPercentClaimed", false);
        guardrail.addProperty("fieldTestedHostileModSecurity", false);
        guardrail.addProperty("notes",
            "This report checks alpha evidence artifacts and docs guardrails only; it does not run Minecraft or prove field readiness.");
        return guardrail;
    }

    private static com.google.gson.JsonArray gateCommands() {
        com.google.gson.JsonArray commands = new com.google.gson.JsonArray();
        commands.add("./gradlew :app:test --stacktrace --no-daemon");
        commands.add("./gradlew :app:strictSecurity --stacktrace --no-daemon");
        commands.add("./gradlew :app:verifyRuntime --stacktrace --no-daemon");
        return commands;
    }

    private static com.google.gson.JsonArray evidenceArtifacts(Path projectRoot, ArtifactSummary summary) {
        com.google.gson.JsonArray artifacts = new com.google.gson.JsonArray();
        artifacts.add(artifact(projectRoot, "app/build/reports/tests", true, summary));
        artifacts.add(artifact(projectRoot, "app/build/test-results", true, summary));
        artifacts.add(artifact(projectRoot, "app/build/reports/microbench", true, summary));
        artifacts.add(artifact(projectRoot, "app/build/reports/soak", true, summary));
        artifacts.add(artifact(projectRoot, "app/build/reports/startup/warm-cache-startup.txt", true, summary));
        artifacts.add(artifact(projectRoot, "app/build/reports/observability/observability-evidence.txt", true, summary));
        artifacts.add(artifact(projectRoot, "app/build/reports/observability/intermed-metrics.json", true, summary));
        return artifacts;
    }

    private static com.google.gson.JsonArray documentationGuardrails(Path projectRoot, ArtifactSummary summary) {
        com.google.gson.JsonArray docs = new com.google.gson.JsonArray();
        docs.add(doc(projectRoot, "README.md", "v8.0-alpha-snapshot", summary));
        docs.add(doc(projectRoot, "COMPLIANCE.md", "v8.0-alpha-snapshot", summary));
        docs.add(doc(projectRoot, "LAUNCH_CRITERIA.md", "v8.0-alpha-snapshot", summary));
        docs.add(doc(projectRoot, "docs/user-guide.md", "v8.0-alpha-snapshot", summary));
        docs.add(doc(projectRoot, "docs/known-limitations.md", "v8.0-alpha-snapshot", summary));
        docs.add(doc(projectRoot, "docs/alpha-triage.md", "v8.0-alpha-snapshot", summary));
        return docs;
    }

    private static JsonObject compatibility(JsonObject apiGapMatrix,
                                            JsonObject corpus,
                                            JsonObject sweepMatrix,
                                            Path harnessResults) {
        JsonObject compatibility = new JsonObject();
        compatibility.add("apiGapSummary", copyObject(apiGapMatrix, "summary"));
        compatibility.add("corpusSummary", copyObject(corpus, "summary"));
        compatibility.add("sweepSummary", copyObject(sweepMatrix, "summary"));
        compatibility.addProperty("sweepEvidenceLevel",
            sweepMatrix.has("scope") && sweepMatrix.get("scope").isJsonObject()
                ? sweepMatrix.getAsJsonObject("scope").get("evidenceLevel").getAsString()
                : "unknown");
        compatibility.addProperty("harnessResultsPresent", harnessResults != null && Files.isRegularFile(harnessResults));
        return compatibility;
    }

    private static JsonObject artifact(Path projectRoot,
                                       String relativePath,
                                       boolean required,
                                       ArtifactSummary summary) {
        Path path = projectRoot.resolve(relativePath).normalize();
        boolean exists = Files.exists(path);
        boolean nonEmpty = exists && nonEmpty(path);
        JsonObject artifact = new JsonObject();
        artifact.addProperty("path", relativePath);
        artifact.addProperty("requiredForAlpha", required);
        artifact.addProperty("exists", exists);
        artifact.addProperty("nonEmpty", nonEmpty);
        artifact.addProperty("status", nonEmpty ? "present" : exists ? "empty" : "missing");
        artifact.addProperty("bytes", byteCount(path));
        summary.add(required, nonEmpty);
        return artifact;
    }

    private static JsonObject doc(Path projectRoot,
                                  String relativePath,
                                  String requiredText,
                                  ArtifactSummary summary) {
        Path path = projectRoot.resolve(relativePath).normalize();
        boolean exists = Files.isRegularFile(path);
        boolean mentionsScope = exists && contains(path, requiredText);
        JsonObject doc = new JsonObject();
        doc.addProperty("path", relativePath);
        doc.addProperty("requiredText", requiredText);
        doc.addProperty("exists", exists);
        doc.addProperty("mentionsScope", mentionsScope);
        doc.addProperty("status", mentionsScope ? "present" : exists ? "scope-missing" : "missing");
        summary.add(true, mentionsScope);
        return doc;
    }

    private static JsonObject copyObject(JsonObject source, String key) {
        if (source == null || !source.has(key) || !source.get(key).isJsonObject()) {
            return new JsonObject();
        }
        return source.getAsJsonObject(key).deepCopy();
    }

    private static boolean nonEmpty(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.list(path)) {
                    return stream.findAny().isPresent();
                }
            }
            return Files.isRegularFile(path) && Files.size(path) > 0L;
        } catch (Exception e) {
            return false;
        }
    }

    private static long byteCount(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static boolean contains(Path path, String text) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(text);
        } catch (Exception e) {
            return false;
        }
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static final class ArtifactSummary {
        private int required;
        private int present;
        private final List<String> notes = new ArrayList<>();

        private void add(boolean isRequired, boolean isPresent) {
            if (!isRequired) {
                return;
            }
            required++;
            if (isPresent) {
                present++;
            }
        }

        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("requiredChecks", required);
            json.addProperty("presentChecks", present);
            json.addProperty("missingChecks", required - present);
            json.addProperty("alphaEvidenceComplete", required == present);
            notes.add("Run the listed Gradle gates separately; this report only checks artifact presence and guardrails.");
            com.google.gson.JsonArray array = new com.google.gson.JsonArray();
            notes.forEach(array::add);
            json.add("notes", array);
            return json;
        }
    }
}
