package org.intermed.core.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.core.InterMedVersion;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.metadata.ModMetadataParser;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.resolver.PubGrubResolver;
import org.intermed.core.resolver.ResolutionDiagnostic;
import org.intermed.core.resolver.ResolutionException;
import org.intermed.core.resolver.ResolvedPlan;
import org.intermed.core.security.SecurityPolicy;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a single triage artifact for external alpha failures.
 *
 * <p>The bundle intentionally reuses existing report generators instead of
 * inventing a new truth source: compatibility report, SBOM, dependency plan,
 * security/config snapshots, and selected logs/runtime reports are collected
 * into one zip that can be uploaded to CI or attached to an issue.</p>
 */
public final class LaunchDiagnosticsBundle {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int MAX_LOG_FILES = 12;
    private static final long MAX_ARTIFACT_BYTES = 2L * 1024L * 1024L;

    private LaunchDiagnosticsBundle() {}

    public static BundleResult writeBundle(Path output,
                                           List<File> jars,
                                           Path gameDir,
                                           Path modsDir,
                                           Path configDir) throws Exception {
        return writeBundle(output, jars, gameDir, modsDir, configDir, null);
    }

    public static BundleResult writeBundle(Path output,
                                           List<File> jars,
                                           Path gameDir,
                                           Path modsDir,
                                           Path configDir,
                                           Path harnessResults) throws Exception {
        Path archive = output.toAbsolutePath().normalize();
        if (archive.getParent() != null) {
            Files.createDirectories(archive.getParent());
        }

        BundleContents contents = buildContents(
            jars == null ? List.of() : jars,
            normalize(gameDir),
            normalize(modsDir),
            normalize(configDir),
            normalize(harnessResults)
        );

        try (OutputStream fileOut = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(fileOut, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, byte[]> entry : contents.entries().entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }

        return new BundleResult(archive, contents.manifest(), List.copyOf(contents.entries().keySet()));
    }

    public static JsonObject generateManifest(List<File> jars,
                                              Path gameDir,
                                              Path modsDir,
                                              Path configDir) {
        return generateManifest(jars, gameDir, modsDir, configDir, null);
    }

    public static JsonObject generateManifest(List<File> jars,
                                              Path gameDir,
                                              Path modsDir,
                                              Path configDir,
                                              Path harnessResults) {
        return buildContents(
            jars == null ? List.of() : jars,
            normalize(gameDir),
            normalize(modsDir),
            normalize(configDir),
            normalize(harnessResults)
        ).manifest();
    }

    private static BundleContents buildContents(List<File> jars,
                                                Path gameDir,
                                                Path modsDir,
                                                Path configDir,
                                                Path harnessResults) {
        LinkedHashMap<String, byte[]> entries = new LinkedHashMap<>();
        JsonArray artifactIndex = new JsonArray();

        JsonObject compatibilityCorpus = CompatibilityCorpusGenerator.generate(jars);
        JsonObject harnessResultsJson = readJsonIfExists(harnessResults);
        putJson(entries, "reports/compatibility-report.json", CompatibilityReportGenerator.generate(jars));
        putJson(entries, "reports/compatibility-corpus.json", compatibilityCorpus);
        putJson(entries, "reports/compatibility-sweep-matrix.json",
            CompatibilitySweepMatrixGenerator.generate(compatibilityCorpus, harnessResultsJson));
        putJson(entries, "reports/sbom.cdx.json", ModSbomGenerator.generate(jars));
        putJson(entries, "reports/api-gap-matrix.json", ApiGapMatrixGenerator.generate());
        putJson(entries, "reports/dependency-plan.json", dependencyPlan(jars));
        putJson(entries, "reports/security-report.json", securityReport(jars, configDir));
        putJson(entries, "reports/runtime-config.json", runtimeConfig(gameDir, modsDir, configDir));
        putJson(entries, "reports/launch-readiness-report.json",
            LaunchReadinessReportGenerator.generate(Path.of("."), gameDir, modsDir, harnessResults));

        copyIfExists(entries, artifactIndex, harnessResults, "artifacts/harness/results.json");
        collectRuntimeArtifacts(entries, artifactIndex, gameDir);
        collectBuildReports(entries, artifactIndex);

        JsonObject manifest = manifest(jars, gameDir, modsDir, configDir, entries.keySet(), artifactIndex);
        putJsonFirst(entries, "manifest.json", manifest);
        return new BundleContents(manifest, entries);
    }

    private static JsonObject readJsonIfExists(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return null;
        }
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static JsonObject manifest(List<File> jars,
                                       Path gameDir,
                                       Path modsDir,
                                       Path configDir,
                                       Set<String> entryNames,
                                       JsonArray artifactIndex) {
        JsonObject root = new JsonObject();
        root.addProperty("schema", "intermed-launch-diagnostics-bundle-v1");
        root.addProperty("intermedVersion", InterMedVersion.BUILD_VERSION);
        root.addProperty("generatedAt", Instant.now().toString());
        root.addProperty("javaVersion", System.getProperty("java.version", "unknown"));
        root.addProperty("os", System.getProperty("os.name", "unknown"));
        root.addProperty("gameDir", display(gameDir));
        root.addProperty("modsDir", display(modsDir));
        root.addProperty("configDir", display(configDir));
        root.addProperty("modJarCount", jars.stream().filter(LaunchDiagnosticsBundle::isJar).count());
        root.addProperty("modArtifactCount", jars.size());
        root.addProperty("entryCount", entryNames.size() + 1);
        root.add("entries", stringArray(entryNames));
        root.add("artifacts", artifactIndex);
        root.add("launchReadiness", launchReadiness());
        return root;
    }

    private static JsonObject launchReadiness() {
        JsonObject readiness = new JsonObject();
        readiness.addProperty("status", "alpha-triage-artifact");
        readiness.addProperty("securityLane", RuntimeConfig.get().isSecurityStrictMode()
            ? "strict"
            : "permissive");
        readiness.addProperty("compatibilityLaneIsSecurityProof", false);
        readiness.addProperty("fieldTested", false);
        readiness.addProperty("notes",
            "This bundle supports external alpha triage. It is not a production/stable compatibility claim.");
        return readiness;
    }

    private static boolean isJar(File file) {
        return file != null && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".jar");
    }

    private static JsonObject dependencyPlan(List<File> jars) {
        JsonObject root = new JsonObject();
        JsonArray candidates = new JsonArray();
        ArrayList<PubGrubResolver.ModManifest> manifests = new ArrayList<>();
        JsonArray parseErrors = new JsonArray();

        for (File jar : jars) {
            try {
                Optional<NormalizedModMetadata> parsed = ModMetadataParser.parse(jar);
                JsonObject candidate = new JsonObject();
                candidate.addProperty("file", jar.getName());
                candidate.addProperty("path", jar.toPath().toAbsolutePath().normalize().toString());
                candidate.addProperty("sha256", sha256(jar.toPath()));
                if (parsed.isEmpty()) {
                    Optional<DataDrivenArchiveDetector.Artifact> dataDriven = DataDrivenArchiveDetector.detect(jar);
                    if (dataDriven.isPresent()) {
                        DataDrivenArchiveDetector.Artifact artifact = dataDriven.get();
                        candidate.addProperty("status", "data-driven");
                        candidate.addProperty("id", artifact.id());
                        candidate.addProperty("version", artifact.version());
                        candidate.addProperty("platform", artifact.platform());
                        candidate.addProperty("artifactType", artifact.artifactType());
                        candidate.add("dependencies", new JsonArray());
                        candidates.add(candidate);
                        continue;
                    }
                    candidate.addProperty("status", "unsupported");
                    candidates.add(candidate);
                    continue;
                }
                NormalizedModMetadata mod = parsed.get();
                candidate.addProperty("status", "parsed");
                candidate.addProperty("id", mod.id());
                candidate.addProperty("version", mod.version());
                candidate.addProperty("platform", mod.platform().name());
                candidate.add("dependencies", dependencyArray(mod.dependencyConstraints()));
                candidates.add(candidate);
                manifests.add(new PubGrubResolver.ModManifest(
                    mod.id(),
                    mod.version(),
                    mod.dependencyConstraints().entrySet().stream()
                        .map(entry -> new PubGrubResolver.ModManifest.DepSpec(entry.getKey(), entry.getValue()))
                        .toList()
                ));
            } catch (Exception e) {
                JsonObject error = new JsonObject();
                error.addProperty("file", jar.getName());
                error.addProperty("status", "failed");
                error.addProperty("error", e.getMessage());
                parseErrors.add(error);
            }
        }

        root.addProperty("schema", "intermed-dependency-plan-v1");
        root.add("candidates", candidates);
        root.add("parseErrors", parseErrors);
        try {
            ResolvedPlan plan = new PubGrubResolver().resolvePlan(manifests);
            root.addProperty("status", "resolved");
            root.add("resolvedVersions", stringMap(plan.resolvedVersions()));
            root.add("dependencyEdges", setMap(plan.dependencyEdges()));
            root.add("loadOrder", stringArray(plan.loadOrder()));
            root.add("softMissingDependencies", stringArray(plan.softMissingDependencies()));
            root.add("moduleKinds", enumMap(plan.moduleKinds()));
            root.add("diagnostics", diagnostics(plan.diagnostics()));
        } catch (ResolutionException e) {
            root.addProperty("status", "failed");
            root.addProperty("errorCode", e.code());
            root.addProperty("moduleId", e.moduleId());
            root.addProperty("message", e.getMessage());
            root.add("requirements", stringArray(e.requirements()));
            root.add("availableVersions", stringArray(e.availableVersions()));
        } catch (Exception e) {
            root.addProperty("status", "failed");
            root.addProperty("message", e.getMessage());
        }
        return root;
    }

    private static JsonObject securityReport(List<File> jars, Path configDir) {
        JsonObject root = new JsonObject();
        Path externalProfiles = configDir == null
            ? null
            : configDir.resolve(SecurityPolicy.EXTERNAL_PROFILES_FILE).toAbsolutePath().normalize();
        root.addProperty("schema", "intermed-security-report-v1");
        root.addProperty("strictMode", RuntimeConfig.get().isSecurityStrictMode());
        root.addProperty("legacyBroadPermissionDefaults", RuntimeConfig.get().isLegacyBroadPermissionDefaultsEnabled());
        root.addProperty("nativeSandboxFallbackEnabled", RuntimeConfig.get().isNativeSandboxFallbackEnabled());
        root.addProperty("externalProfilesFile", externalProfiles == null ? "" : externalProfiles.toString());
        root.addProperty("externalProfilesPresent", externalProfiles != null && Files.exists(externalProfiles));

        JsonArray mods = new JsonArray();
        for (File jar : jars) {
            JsonObject entry = new JsonObject();
            entry.addProperty("file", jar.getName());
            try {
                Optional<NormalizedModMetadata> parsed = ModMetadataParser.parse(jar);
                if (parsed.isEmpty()) {
                    Optional<DataDrivenArchiveDetector.Artifact> dataDriven = DataDrivenArchiveDetector.detect(jar);
                    if (dataDriven.isPresent()) {
                        DataDrivenArchiveDetector.Artifact artifact = dataDriven.get();
                        entry.addProperty("status", "data-driven");
                        entry.addProperty("id", artifact.id());
                        entry.addProperty("platform", artifact.platform());
                        entry.addProperty("artifactType", artifact.artifactType());
                        entry.add("declaredPermissions", new JsonArray());
                        entry.add("granularSecurity", new JsonObject());
                        entry.addProperty("hasGranularSecurity", false);
                    } else {
                        entry.addProperty("status", "unsupported");
                    }
                } else {
                    NormalizedModMetadata mod = parsed.get();
                    JsonObject manifest = mod.manifest();
                    entry.addProperty("status", "parsed");
                    entry.addProperty("id", mod.id());
                    entry.addProperty("platform", mod.platform().name());
                    entry.add("declaredPermissions", copyOrEmptyArray(manifest.get("intermed:permissions")));
                    entry.add("granularSecurity", copyOrEmptyObject(manifest.get("intermed:security")));
                    entry.addProperty("hasGranularSecurity", manifest.has("intermed:security"));
                }
            } catch (Exception e) {
                entry.addProperty("status", "failed");
                entry.addProperty("error", e.getMessage());
            }
            mods.add(entry);
        }
        root.add("mods", mods);
        return root;
    }

    private static JsonObject runtimeConfig(Path gameDir, Path modsDir, Path configDir) {
        RuntimeConfig config = RuntimeConfig.get();
        JsonObject root = new JsonObject();
        root.addProperty("schema", "intermed-runtime-config-v1");
        root.addProperty("cacheFingerprint", config.cacheFingerprint());
        root.addProperty("gameDir", display(gameDir));
        root.addProperty("modsDir", display(modsDir));
        root.addProperty("configDir", display(configDir));
        root.addProperty("environment", config.getEnvironmentType().name());
        root.addProperty("aotCacheEnabled", config.isAotCacheEnabled());
        root.addProperty("securityStrictMode", config.isSecurityStrictMode());
        root.addProperty("legacyBroadPermissionDefaults", config.isLegacyBroadPermissionDefaultsEnabled());
        root.addProperty("resolverFallbackEnabled", config.isResolverFallbackEnabled());
        root.addProperty("mixinAstReclaimEnabled", config.isMixinAstReclaimEnabled());
        root.addProperty("mixinConflictPolicy", config.getMixinConflictPolicy());
        root.addProperty("classloaderDynamicWeakEdgesEnabled", config.isClassloaderDynamicWeakEdgesEnabled());
        root.addProperty("vfsEnabled", config.isVfsEnabled());
        root.addProperty("vfsConflictPolicy", config.getVfsConflictPolicy());
        root.add("vfsPriorityOrder", stringArray(config.getVfsPriorityOrder()));
        root.addProperty("vfsCacheDir", config.getVfsCacheDir().toString());
        root.addProperty("espressoSandboxEnabled", config.isEspressoSandboxEnabled());
        root.addProperty("wasmSandboxEnabled", config.isWasmSandboxEnabled());
        root.addProperty("nativeSandboxFallbackEnabled", config.isNativeSandboxFallbackEnabled());
        root.addProperty("sandboxSharedRegionBytes", config.getSandboxSharedRegionBytes());
        root.addProperty("sandboxSharedRegionPoolMax", config.getSandboxSharedRegionPoolMax());
        root.addProperty("prometheusEnabled", config.isPrometheusEnabled());
        root.addProperty("prometheusPort", config.getPrometheusPort());
        root.addProperty("otelEnabled", config.isOtelEnabled());
        root.addProperty("otelOutputPath", config.getOtelOutputPath().toString());
        return root;
    }

    private static void collectRuntimeArtifacts(Map<String, byte[]> entries, JsonArray artifactIndex, Path gameDir) {
        if (gameDir == null) {
            return;
        }
        collectLogFiles(entries, artifactIndex, gameDir.resolve("logs"));
        copyIfExists(entries, artifactIndex, gameDir.resolve(".intermed/vfs/diagnostics.json"),
            "artifacts/vfs/diagnostics.json");
        copyIfExists(entries, artifactIndex, gameDir.resolve("config").resolve(SecurityPolicy.EXTERNAL_PROFILES_FILE),
            "artifacts/security/" + SecurityPolicy.EXTERNAL_PROFILES_FILE);
        copyIfExists(entries, artifactIndex, gameDir.resolve(".intermed/aot_v8/metadata/index.json"),
            "artifacts/aot/index.json");
    }

    private static void collectBuildReports(Map<String, byte[]> entries, JsonArray artifactIndex) {
        List<Path> reportRoots = List.of(
            Path.of("app", "build", "reports"),
            Path.of("build", "reports")
        );
        List<String> relativeReports = List.of(
            "startup/warm-cache-startup.txt",
            "observability/observability-evidence.txt",
            "observability/intermed-metrics.json",
            "microbench/registry-hot-path.txt",
            "microbench/remapper-hot-path.txt",
            "microbench/event-bus-hot-path.txt",
            "soak/runtime-soak.txt"
        );
        for (Path root : reportRoots) {
            for (String relative : relativeReports) {
                Path report = root.resolve(relative);
                copyIfExists(entries, artifactIndex, report,
                    "artifacts/build-reports/" + root.toString().replace('\\', '_').replace('/', '_') + "/" + relative);
            }
        }
    }

    private static void collectLogFiles(Map<String, byte[]> entries, JsonArray artifactIndex, Path logsDir) {
        if (logsDir == null || !Files.isDirectory(logsDir)) {
            return;
        }
        try {
            List<Path> logs = Files.list(logsDir)
                .filter(Files::isRegularFile)
                .filter(path -> {
                    String name = path.getFileName().toString();
                    return name.endsWith(".log") || name.endsWith(".log.gz") || name.endsWith(".txt");
                })
                .sorted(Comparator
                    .comparingLong((Path path) -> lastModified(path)).reversed()
                    .thenComparing(path -> path.getFileName().toString()))
                .limit(MAX_LOG_FILES)
                .toList();
            for (Path log : logs) {
                copyIfExists(entries, artifactIndex, log, "artifacts/logs/" + sanitize(log.getFileName().toString()));
            }
        } catch (Exception ignored) {
            // Diagnostics collection should never hide the original launch failure.
        }
    }

    private static void copyIfExists(Map<String, byte[]> entries,
                                     JsonArray artifactIndex,
                                     Path source,
                                     String preferredEntryName) {
        if (source == null || !Files.isRegularFile(source)) {
            return;
        }
        try {
            long size = Files.size(source);
            boolean truncated = size > MAX_ARTIFACT_BYTES;
            byte[] bytes = readBounded(source, MAX_ARTIFACT_BYTES);
            String entryName = uniqueEntryName(entries.keySet(), preferredEntryName);
            entries.put(entryName, bytes);
            JsonObject artifact = new JsonObject();
            artifact.addProperty("entry", entryName);
            artifact.addProperty("source", source.toAbsolutePath().normalize().toString());
            artifact.addProperty("bytes", size);
            artifact.addProperty("truncated", truncated);
            artifact.addProperty("sha256", truncated ? "truncated" : sha256(source));
            artifactIndex.add(artifact);
        } catch (Exception ignored) {
            // Best-effort artifact collection.
        }
    }

    private static byte[] readBounded(Path source, long maxBytes) throws Exception {
        try (InputStream input = Files.newInputStream(source)) {
            int limit = (int) Math.min(Integer.MAX_VALUE, Math.max(0L, maxBytes));
            return input.readNBytes(limit);
        }
    }

    private static void putJson(Map<String, byte[]> entries, String entryName, JsonObject json) {
        entries.put(entryName, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
    }

    private static void putJsonFirst(LinkedHashMap<String, byte[]> entries, String entryName, JsonObject json) {
        LinkedHashMap<String, byte[]> reordered = new LinkedHashMap<>();
        reordered.put(entryName, GSON.toJson(json).getBytes(StandardCharsets.UTF_8));
        entries.forEach(reordered::putIfAbsent);
        entries.clear();
        entries.putAll(reordered);
    }

    private static JsonArray dependencyArray(Map<String, String> constraints) {
        JsonArray array = new JsonArray();
        constraints.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                JsonObject dep = new JsonObject();
                dep.addProperty("id", entry.getKey());
                dep.addProperty("version", entry.getValue());
                array.add(dep);
            });
        return array;
    }

    private static JsonObject stringMap(Map<String, String> values) {
        JsonObject json = new JsonObject();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> json.addProperty(entry.getKey(), entry.getValue()));
        return json;
    }

    private static JsonObject enumMap(Map<String, ? extends Enum<?>> values) {
        JsonObject json = new JsonObject();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> json.addProperty(entry.getKey(), entry.getValue().name()));
        return json;
    }

    private static JsonObject setMap(Map<String, Set<String>> values) {
        JsonObject json = new JsonObject();
        values.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> json.add(entry.getKey(), stringArray(entry.getValue())));
        return json;
    }

    private static JsonArray diagnostics(List<ResolutionDiagnostic> diagnostics) {
        JsonArray array = new JsonArray();
        for (ResolutionDiagnostic diagnostic : diagnostics) {
            JsonObject json = new JsonObject();
            json.addProperty("severity", diagnostic.severity().name());
            json.addProperty("code", diagnostic.code());
            json.addProperty("moduleId", diagnostic.moduleId());
            json.addProperty("message", diagnostic.message());
            array.add(json);
        }
        return array;
    }

    private static JsonArray stringArray(Iterable<String> values) {
        JsonArray array = new JsonArray();
        if (values != null) {
            for (String value : values) {
                array.add(value);
            }
        }
        return array;
    }

    private static JsonArray copyOrEmptyArray(JsonElement element) {
        if (element != null && element.isJsonArray()) {
            return element.getAsJsonArray().deepCopy();
        }
        return new JsonArray();
    }

    private static JsonObject copyOrEmptyObject(JsonElement element) {
        if (element != null && element.isJsonObject()) {
            return element.getAsJsonObject().deepCopy();
        }
        return new JsonObject();
    }

    private static String sha256(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String uniqueEntryName(Set<String> existing, String preferred) {
        String candidate = preferred;
        int counter = 2;
        while (existing.contains(candidate)) {
            int dot = preferred.lastIndexOf('.');
            if (dot > 0) {
                candidate = preferred.substring(0, dot) + "-" + counter + preferred.substring(dot);
            } else {
                candidate = preferred + "-" + counter;
            }
            counter++;
        }
        return candidate;
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static String display(Path path) {
        return path == null ? "" : path.toString();
    }

    public record BundleResult(Path archive, JsonObject manifest, List<String> entries) {}

    private record BundleContents(JsonObject manifest, LinkedHashMap<String, byte[]> entries) {}
}
