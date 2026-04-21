package org.intermed.core.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.intermed.core.InterMedVersion;
import org.intermed.core.metadata.ModMetadataParser;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.sandbox.PolyglotSandboxManager;
import org.intermed.core.sandbox.SandboxPlan;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Captures the exact local mod corpus that a compatibility sweep intends to test.
 *
 * <p>This is deliberately weaker than a pass/fail compatibility report: it is a
 * reproducibility artifact for alpha and beta planning. Real launch outcomes
 * should be stored separately and linked back to these stable SHA-256 entries.</p>
 */
public final class CompatibilityCorpusGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private CompatibilityCorpusGenerator() {}

    public static void writeReport(Path output, List<File> jars) throws Exception {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        Files.writeString(output, GSON.toJson(generate(jars)), StandardCharsets.UTF_8);
    }

    public static JsonObject generate(List<File> jars) {
        List<File> sorted = (jars == null ? List.<File>of() : jars).stream()
            .sorted(Comparator.comparing(File::getName))
            .toList();

        JsonArray candidates = new JsonArray();
        CorpusSummary summary = new CorpusSummary();
        for (File jar : sorted) {
            JsonObject candidate = candidate(jar, summary);
            candidates.add(candidate);
        }

        JsonObject root = new JsonObject();
        root.addProperty("schema", "intermed-compatibility-corpus-v1");
        root.addProperty("intermedVersion", InterMedVersion.BUILD_VERSION);
        root.addProperty("generatedAt", Instant.now().toString());
        root.add("scope", scope());
        root.add("truthModel", EvidenceLevel.truthModel(
            summary.parsed > 0 ? List.of(EvidenceLevel.PARSED) : List.of(),
            "Corpus evidence is limited to discovery and manifest parsing until runtime lanes attach stronger proof."
        ));
        root.add("summary", summary.toJson());
        root.add("candidates", candidates);
        return root;
    }

    private static JsonObject scope() {
        JsonObject scope = new JsonObject();
        scope.addProperty("minecraft", "1.20.1");
        scope.addProperty("evidenceLevel", EvidenceLevel.PARSED.name());
        scope.addProperty("legacyEvidenceLevel", "manifest-only");
        scope.addProperty("claim",
            EvidenceLevel.PARSED.description());
        JsonArray ecosystems = new JsonArray();
        ecosystems.add("Fabric");
        ecosystems.add("Forge");
        ecosystems.add("NeoForge");
        scope.add("ecosystems", ecosystems);
        return scope;
    }

    private static JsonObject candidate(File jar, CorpusSummary summary) {
        summary.total++;
        JsonObject entry = new JsonObject();
        entry.addProperty("file", jar.getName());
        entry.addProperty("sha256", sha256(jar.toPath()));
        entry.addProperty("sizeBytes", safeSize(jar.toPath()));
        entry.addProperty("expectedOutcome", "unclassified");
        entry.addProperty("sweepStatus", "not-run");
        entry.addProperty("fieldTested", false);

        try {
            Optional<NormalizedModMetadata> parsed = ModMetadataParser.parse(jar);
            if (parsed.isEmpty()) {
                Optional<DataDrivenArchiveDetector.Artifact> dataDriven = DataDrivenArchiveDetector.detect(jar);
                if (dataDriven.isPresent()) {
                    return dataDrivenCandidate(entry, dataDriven.get(), summary);
                }
                summary.unsupported++;
                summary.status("unsupported");
                entry.addProperty("status", "unsupported");
                entry.addProperty("reason", "no supported Fabric/Forge/NeoForge manifest found");
                return entry;
            }

            NormalizedModMetadata mod = parsed.get();
            summary.parsed++;
            summary.status("parsed");
            summary.platform(mod.platform().name());
            if (!mod.mixinConfigs().isEmpty()) {
                summary.withMixins++;
            }
            if (mod.hasClientResources()) {
                summary.withClientResources++;
            }
            if (mod.hasServerData()) {
                summary.withServerData++;
            }

            SandboxPlan sandbox = PolyglotSandboxManager.planFor(mod);
            entry.addProperty("status", "parsed");
            entry.addProperty("id", mod.id());
            entry.addProperty("name", mod.name());
            entry.addProperty("version", mod.version());
            entry.addProperty("platform", mod.platform().name());
            entry.addProperty("clientResources", mod.hasClientResources());
            entry.addProperty("serverData", mod.hasServerData());
            entry.addProperty("mixinConfigCount", mod.mixinConfigs().size());
            entry.addProperty("dependencyCount", mod.dependencyConstraints().size());
            entry.add("dependencies", stringMap(mod.dependencyConstraints()));
            entry.add("softDependencies", stringArray(mod.softDependencyIds()));
            entry.add("allowedPeers", stringArray(mod.allowedPeerIds()));
            entry.add("weakApiPrefixes", stringArray(mod.weakApiPrefixes()));
            entry.add("mixins", stringArray(mod.mixinConfigs()));
            entry.add("entrypoints", entrypoints(mod));
            entry.add("sandbox", sandbox.toJson());
            return entry;
        } catch (Exception e) {
            summary.failed++;
            summary.status("failed");
            entry.addProperty("status", "failed");
            entry.addProperty("error", e.getMessage());
            return entry;
        }
    }

    private static JsonObject dataDrivenCandidate(JsonObject entry,
                                                  DataDrivenArchiveDetector.Artifact artifact,
                                                  CorpusSummary summary) {
        summary.parsed++;
        summary.status("parsed");
        summary.platform(artifact.platform());
        if (artifact.clientResources()) {
            summary.withClientResources++;
        }
        if (artifact.serverData()) {
            summary.withServerData++;
        }

        entry.addProperty("status", "parsed");
        entry.addProperty("artifactType", artifact.artifactType());
        entry.addProperty("id", artifact.id());
        entry.addProperty("name", artifact.name());
        entry.addProperty("version", artifact.version());
        entry.addProperty("platform", artifact.platform());
        entry.addProperty("clientResources", artifact.clientResources());
        entry.addProperty("serverData", artifact.serverData());
        entry.addProperty("mixinConfigCount", 0);
        entry.addProperty("dependencyCount", 0);
        entry.add("dependencies", new JsonObject());
        entry.add("softDependencies", new JsonArray());
        entry.add("allowedPeers", new JsonArray());
        entry.add("weakApiPrefixes", new JsonArray());
        entry.add("mixins", new JsonArray());
        entry.add("entrypoints", DataDrivenArchiveDetector.emptyEntrypoints());
        entry.add("sandbox", DataDrivenArchiveDetector.sandbox(artifact));
        return entry;
    }

    private static JsonObject entrypoints(NormalizedModMetadata mod) {
        JsonObject entrypoints = new JsonObject();
        entrypoints.add("main", stringArray(mod.entrypoints("main")));
        entrypoints.add("client", stringArray(mod.entrypoints("client")));
        entrypoints.add("server", stringArray(mod.entrypoints("server")));
        return entrypoints;
    }

    private static JsonObject stringMap(Map<String, String> values) {
        JsonObject json = new JsonObject();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> json.addProperty(entry.getKey(), entry.getValue()));
        return json;
    }

    private static JsonArray stringArray(List<String> values) {
        JsonArray array = new JsonArray();
        values.stream().sorted().forEach(array::add);
        return array;
    }

    private static long safeSize(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return -1L;
        }
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static final class CorpusSummary {
        private int total;
        private int parsed;
        private int unsupported;
        private int failed;
        private int withMixins;
        private int withClientResources;
        private int withServerData;
        private final Map<String, Integer> byPlatform = new LinkedHashMap<>();
        private final Map<String, Integer> byStatus = new LinkedHashMap<>();

        private void platform(String platform) {
            byPlatform.merge(platform, 1, Integer::sum);
        }

        private void status(String status) {
            byStatus.merge(status, 1, Integer::sum);
        }

        private JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("total", total);
            json.addProperty("parsed", parsed);
            json.addProperty("unsupported", unsupported);
            json.addProperty("failed", failed);
            json.addProperty("unclassified", total);
            json.addProperty("notRun", total);
            json.addProperty("withMixins", withMixins);
            json.addProperty("withClientResources", withClientResources);
            json.addProperty("withServerData", withServerData);
            json.add("byPlatform", intMap(byPlatform));
            json.add("byStatus", intMap(byStatus));
            return json;
        }

        private static JsonObject intMap(Map<String, Integer> values) {
            JsonObject json = new JsonObject();
            values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> json.addProperty(entry.getKey(), entry.getValue()));
            return json;
        }
    }

}
