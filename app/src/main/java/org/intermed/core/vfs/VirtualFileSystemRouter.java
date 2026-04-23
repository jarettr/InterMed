package org.intermed.core.vfs;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.intermed.core.cache.AOTCacheManager;
import org.intermed.core.config.RuntimeConfig;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.metadata.RuntimeModIndex;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * Central router for data-driven resources. It discovers data/assets from loaded
 * mods, detects collisions, resolves JSON conflicts, and emits an overlay pack
 * that can be mounted ahead of the original jars.
 */
public final class VirtualFileSystemRouter {

    private static final Object CACHE_LOCK = new Object();

    private static volatile String cachedFingerprint;
    private static volatile MountPlan cachedPlan;

    private VirtualFileSystemRouter() {}

    public static MountPlan buildRuntimeMountPlan() {
        RuntimeConfig config = RuntimeConfig.get();
        VirtualFileSystemOverrides overrides = VirtualFileSystemOverrides.load(config.getConfigDir());
        List<NormalizedModMetadata> mods = RuntimeModIndex.allMods().stream()
            .filter(VirtualFileSystemRouter::hasAnyResources)
            .filter(metadata -> metadata.sourceJar() != null && metadata.sourceJar().isFile())
            .sorted(Comparator.comparing(NormalizedModMetadata::id))
            .toList();
        String fingerprint = fingerprint(mods, config, overrides);
        MountPlan snapshot = cachedPlan;
        if (snapshot != null && Objects.equals(cachedFingerprint, fingerprint)) {
            return snapshot;
        }
        synchronized (CACHE_LOCK) {
            if (cachedPlan != null && Objects.equals(cachedFingerprint, fingerprint)) {
                return cachedPlan;
            }
            MountPlan rebuilt = buildPlan(mods, config, overrides);
            cachedFingerprint = fingerprint;
            cachedPlan = rebuilt;
            return rebuilt;
        }
    }

    public static void invalidateCache() {
        synchronized (CACHE_LOCK) {
            cachedFingerprint = null;
            cachedPlan = null;
        }
    }

    public static boolean isManagedResourcePath(String path) {
        return path != null && (path.startsWith("assets/") || path.startsWith("data/"));
    }

    public static URL resolveManagedResource(String path) {
        if (!isManagedResourcePath(path)) {
            return null;
        }
        List<URL> resources = resolveManagedResources(path);
        return resources.isEmpty() ? null : resources.getFirst();
    }

    public static List<URL> resolveManagedResources(String path) {
        if (!isManagedResourcePath(path)) {
            return List.of();
        }
        return buildRuntimeMountPlan().resolve(path);
    }

    static MountPlan buildPlan(Collection<NormalizedModMetadata> mods, RuntimeConfig config) {
        return buildPlan(mods, config, VirtualFileSystemOverrides.load(config.getConfigDir()));
    }

    private static MountPlan buildPlan(Collection<NormalizedModMetadata> mods,
                                       RuntimeConfig config,
                                       VirtualFileSystemOverrides overrides) {
        List<NormalizedModMetadata> candidates = mods == null
            ? List.of()
            : mods.stream()
                .filter(VirtualFileSystemRouter::hasAnyResources)
                .filter(metadata -> metadata.sourceJar() != null && metadata.sourceJar().isFile())
                .sorted(Comparator.comparing(NormalizedModMetadata::id))
                .toList();

        LinkedHashSet<File> basePacks = new LinkedHashSet<>();
        for (NormalizedModMetadata metadata : candidates) {
            basePacks.add(ensureMountablePack(metadata, config));
        }
        if (!config.isVfsEnabled() || candidates.isEmpty()) {
            return new MountPlan(List.copyOf(basePacks), null, List.of(), 0, Map.of(), null);
        }

        Map<String, Integer> precedenceByMod = precedenceByMod(candidates, config.getVfsPriorityOrder());
        Map<String, List<ResourceContribution>> contributions = discoverResources(candidates, precedenceByMod);
        LinkedHashMap<String, byte[]> overlayEntries = new LinkedHashMap<>();
        List<ResourceConflict> conflicts = new ArrayList<>();
        int mergedJsonResources = 0;

        for (Map.Entry<String, List<ResourceContribution>> entry : contributions.entrySet()) {
            List<ResourceContribution> values = entry.getValue();
            if (values.size() < 2) {
                continue;
            }
            Resolution resolution = resolve(
                entry.getKey(),
                values,
                config.getVfsConflictPolicy(),
                overrides.match(entry.getKey())
            );
            overlayEntries.put(entry.getKey(), resolution.bytes());
            conflicts.add(resolution.conflict());
            if (resolution.conflict().jsonMerged()) {
                mergedJsonResources++;
            }
        }

        File overlayPack = overlayEntries.isEmpty()
            ? null
            : materializeOverlayPack(config, candidates, overlayEntries, conflicts);

        List<File> mountable = new ArrayList<>();
        if (overlayPack != null) {
            mountable.add(overlayPack);
        }
        mountable.addAll(basePacks);
        LinkedHashMap<String, List<ResourceLocator>> resourceIndex = buildResourceIndex(
            contributions,
            overlayEntries,
            overlayPack
        );
        File diagnosticsReport = materializeDiagnosticsReport(
            config,
            candidates,
            mountable,
            overlayPack,
            contributions,
            overlayEntries,
            conflicts,
            mergedJsonResources
        );
        return new MountPlan(
            List.copyOf(mountable),
            overlayPack,
            List.copyOf(conflicts),
            mergedJsonResources,
            resourceIndex,
            diagnosticsReport
        );
    }

    private static Resolution resolve(String resourcePath,
                                      List<ResourceContribution> contributions,
                                      String configuredPolicy,
                                      VirtualFileSystemOverrides.Rule overrideRule) {
        String effectivePolicy = overrideRule != null && overrideRule.policy() != null
            ? overrideRule.policy()
            : configuredPolicy;
        String policySource = overrideRule != null ? overrideRule.policySource() : "runtime-default";
        List<ResourceContribution> ordered = contributions.stream()
            .sorted(Comparator.comparingInt(
                (ResourceContribution contribution) -> effectivePriority(contribution, overrideRule)
            ).reversed())
            .toList();
        ResourceContribution winner = contributions.stream()
            .min(Comparator.comparingInt(
                (ResourceContribution contribution) -> effectivePriority(contribution, overrideRule)
            ))
            .orElseThrow();
        List<String> modIds = ordered.stream().map(ResourceContribution::modId).toList();

        if (isJson(resourcePath) && !"priority".equalsIgnoreCase(effectivePolicy)) {
            try {
                List<JsonPatchMergeEngine.JsonSource> jsonSources = ordered.stream()
                    .map(contribution -> new JsonPatchMergeEngine.JsonSource(
                        contribution.modId(),
                        JsonParser.parseString(new String(contribution.bytes(), StandardCharsets.UTF_8))
                    ))
                    .toList();

                // Use CRDT merge for array-heavy paths (tags, loot tables, worldgen) to
                // eliminate ordering-dependent conflicts (ТЗ 3.5.6); fall back to JSON Patch
                // for scalar/object-dominated paths.
                final com.google.gson.JsonElement mergedDoc;
                final String mergeStrategy;
                if (isCrdtEligible(resourcePath)) {
                    CrdtJsonMergeEngine.MergeResult crdt = CrdtJsonMergeEngine.merge(resourcePath, jsonSources);
                    mergedDoc     = crdt.mergedDocument();
                    mergeStrategy = "crdt_lww_merge";
                } else {
                    JsonPatchMergeEngine.MergeOutcome outcome = JsonPatchMergeEngine.merge(resourcePath, jsonSources);
                    mergedDoc     = outcome.mergedDocument();
                    mergeStrategy = outcome.structuralConflict() ? "json_patch_merge_with_priority" : "json_patch_merge";
                }

                byte[] bytes = new GsonBuilder()
                    .disableHtmlEscaping()
                    .setPrettyPrinting()
                    .create()
                    .toJson(mergedDoc)
                    .getBytes(StandardCharsets.UTF_8);
                return new Resolution(
                    bytes,
                    new ResourceConflict(
                        resourcePath,
                        classifyResourceKind(resourcePath),
                        modIds,
                        winner.modId(),
                        mergeStrategy,
                        true,
                        policySource
                    )
                );
            } catch (Exception parseFailure) {
                // Fall back to explicit priority resolution for malformed JSON or exotic documents.
            }
        }

        return new Resolution(
            winner.bytes(),
            new ResourceConflict(
                resourcePath,
                classifyResourceKind(resourcePath),
                modIds,
                winner.modId(),
                "priority_override",
                false,
                policySource
            )
        );
    }

    private static Map<String, List<ResourceContribution>> discoverResources(List<NormalizedModMetadata> mods,
                                                                             Map<String, Integer> precedenceByMod) {
        Map<String, List<ResourceContribution>> contributions = new LinkedHashMap<>();
        for (NormalizedModMetadata metadata : mods) {
            int precedence = precedenceByMod.getOrDefault(metadata.id(), Integer.MAX_VALUE);
            try (JarFile jar = new JarFile(metadata.sourceJar())) {
                var entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!isManagedResource(entry)) {
                        continue;
                    }
                    byte[] bytes;
                    try (var input = jar.getInputStream(entry)) {
                        bytes = input.readAllBytes();
                    }
                    contributions
                        .computeIfAbsent(entry.getName(), ignored -> new ArrayList<>())
                        .add(new ResourceContribution(
                            metadata.id(),
                            metadata.sourceJar().getAbsoluteFile(),
                            precedence,
                            bytes
                        ));
                }
            } catch (IOException e) {
                System.err.println("[InterMed VFS] Failed to scan " + metadata.sourceJar().getName() + ": " + e.getMessage());
            }
        }
        return contributions;
    }

    private static Map<String, Integer> precedenceByMod(List<NormalizedModMetadata> mods, List<String> configuredPriority) {
        Map<String, Integer> precedence = new LinkedHashMap<>();
        int next = 0;
        for (String modId : configuredPriority) {
            if (!precedence.containsKey(modId)) {
                precedence.put(modId, next++);
            }
        }
        for (NormalizedModMetadata metadata : mods) {
            precedence.putIfAbsent(metadata.id(), next++);
        }
        return precedence;
    }

    private static File materializeOverlayPack(RuntimeConfig config,
                                               List<NormalizedModMetadata> mods,
                                               LinkedHashMap<String, byte[]> overlayEntries,
                                               List<ResourceConflict> conflicts) {
        try {
            Path cacheDir = config.getVfsCacheDir();
            Files.createDirectories(cacheDir);
            String fingerprint = overlayFingerprint(mods, config, overlayEntries, conflicts);
            Path overlayPath = cacheDir.resolve("intermed-vfs-overlay-" + fingerprint.substring(0, 16) + ".zip");
            if (Files.exists(overlayPath)) {
                return overlayPath.toFile();
            }

            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(overlayPath))) {
                writeEntry(output, "pack.mcmeta", packMetadataBytes());
                writeEntry(output, "intermed-vfs-manifest.json", manifestBytes(conflicts, overlayEntries.size()));
                for (Map.Entry<String, byte[]> entry : overlayEntries.entrySet()) {
                    writeEntry(output, entry.getKey(), entry.getValue());
                }
            }
            return overlayPath.toFile();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to materialize InterMed VFS overlay", e);
        }
    }

    private static byte[] packMetadataBytes() {
        return packMetadataBytes("InterMed VFS Overlay");
    }

    private static byte[] packMetadataBytes(String description) {
        JsonObject root = new JsonObject();
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", 15);
        pack.addProperty("description", description);
        root.add("pack", pack);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static File ensureMountablePack(NormalizedModMetadata metadata, RuntimeConfig config) {
        File sourceJar = metadata.sourceJar().getAbsoluteFile();
        if (jarContainsEntry(sourceJar, "pack.mcmeta")) {
            return sourceJar;
        }
        return materializeSyntheticPack(config, metadata);
    }

    private static boolean jarContainsEntry(File jarFile, String entryName) {
        try (JarFile jar = new JarFile(jarFile)) {
            return jar.getJarEntry(entryName) != null;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect jar " + jarFile.getName() + " for " + entryName, e);
        }
    }

    private static File materializeSyntheticPack(RuntimeConfig config, NormalizedModMetadata metadata) {
        File sourceJar = metadata.sourceJar().getAbsoluteFile();
        try {
            Path cacheDir = config.getVfsCacheDir();
            Files.createDirectories(cacheDir);
            String fingerprint = AOTCacheManager.sha256((
                metadata.id()
                    + "|" + metadata.version()
                    + "|" + sourceJar.getAbsolutePath()
                    + "|" + sourceJar.length()
                    + "|" + sourceJar.lastModified()
            ).getBytes(StandardCharsets.UTF_8));
            Path syntheticPath = cacheDir.resolve(
                "intermed-synth-pack-" + sanitizeFileComponent(metadata.id()) + "-" + fingerprint.substring(0, 16) + ".zip"
            );
            if (Files.exists(syntheticPath)) {
                return syntheticPath.toFile();
            }

            try (JarFile input = new JarFile(sourceJar);
                 JarOutputStream output = new JarOutputStream(Files.newOutputStream(syntheticPath))) {
                writeEntry(output, "pack.mcmeta", packMetadataBytes("InterMed synthetic pack: " + metadata.name()));
                JarEntry icon = input.getJarEntry("pack.png");
                if (icon != null && !icon.isDirectory()) {
                    try (var stream = input.getInputStream(icon)) {
                        writeEntry(output, "pack.png", stream.readAllBytes());
                    }
                }
                var entries = input.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!isManagedResource(entry)) {
                        continue;
                    }
                    try (var stream = input.getInputStream(entry)) {
                        writeEntry(output, entry.getName(), stream.readAllBytes());
                    }
                }
            }
            return syntheticPath.toFile();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to materialize synthetic pack for " + metadata.id(), e);
        }
    }

    private static byte[] manifestBytes(List<ResourceConflict> conflicts, int mergedEntries) {
        JsonObject root = new JsonObject();
        root.addProperty("mergedEntries", mergedEntries);
        root.add("summary", summaryJson(conflicts));
        JsonArray entries = new JsonArray();
        for (ResourceConflict conflict : conflicts) {
            JsonObject item = new JsonObject();
            item.addProperty("path", conflict.path());
            item.addProperty("resourceKind", conflict.resourceKind());
            item.addProperty("winner", conflict.winnerModId());
            item.addProperty("resolution", conflict.resolution());
            item.addProperty("jsonMerged", conflict.jsonMerged());
            item.addProperty("policySource", conflict.policySource());
            JsonArray mods = new JsonArray();
            for (String modId : conflict.contributingMods()) {
                mods.add(modId);
            }
            item.add("mods", mods);
            entries.add(item);
        }
        root.add("conflicts", entries);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root).getBytes(StandardCharsets.UTF_8);
    }

    private static File materializeDiagnosticsReport(RuntimeConfig config,
                                                     List<NormalizedModMetadata> mods,
                                                     List<File> mountablePacks,
                                                     File overlayPack,
                                                     Map<String, List<ResourceContribution>> contributions,
                                                     Map<String, byte[]> overlayEntries,
                                                     List<ResourceConflict> conflicts,
                                                     int mergedJsonResources) {
        try {
            Path cacheDir = config.getVfsCacheDir();
            Files.createDirectories(cacheDir);
            String fingerprint = overlayFingerprint(mods, config, overlayEntries, conflicts);
            Path reportPath = cacheDir.resolve("intermed-vfs-report-" + fingerprint.substring(0, 16) + ".json");
            if (Files.exists(reportPath)) {
                return reportPath.toFile();
            }

            JsonObject report = new JsonObject();
            report.addProperty("generatedAt", Instant.now().toString());
            report.addProperty("overlayPack", overlayPack == null ? null : overlayPack.getAbsolutePath());
            report.addProperty("managedResourcePaths", contributions.size());
            report.addProperty("overlayEntries", overlayEntries.size());
            report.addProperty("mergedJsonResources", mergedJsonResources);
            report.addProperty("conflictCount", conflicts.size());

            JsonArray packs = new JsonArray();
            for (File pack : mountablePacks) {
                packs.add(pack.getAbsolutePath());
            }
            report.add("mountablePacks", packs);
            report.add("summary", summaryJson(conflicts));

            JsonArray resources = new JsonArray();
            contributions.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    JsonObject resource = new JsonObject();
                    resource.addProperty("path", entry.getKey());
                    resource.addProperty("resourceKind", classifyResourceKind(entry.getKey()));
                    JsonArray owners = new JsonArray();
                    for (ResourceContribution contribution : entry.getValue()) {
                        owners.add(contribution.modId());
                    }
                    resource.add("mods", owners);
                    resource.addProperty("resolvedByOverlay", overlayEntries.containsKey(entry.getKey()));
                    resources.add(resource);
                });
            report.add("resources", resources);

            JsonArray conflictEntries = new JsonArray();
            for (ResourceConflict conflict : conflicts) {
                JsonObject item = new JsonObject();
                item.addProperty("path", conflict.path());
                item.addProperty("resourceKind", conflict.resourceKind());
                item.addProperty("winner", conflict.winnerModId());
                item.addProperty("resolution", conflict.resolution());
                item.addProperty("jsonMerged", conflict.jsonMerged());
                item.addProperty("policySource", conflict.policySource());
                JsonArray modsJson = new JsonArray();
                for (String modId : conflict.contributingMods()) {
                    modsJson.add(modId);
                }
                item.add("mods", modsJson);
                JsonObject suggestedOverride = new JsonObject();
                suggestedOverride.addProperty("path", conflict.path());
                suggestedOverride.addProperty("policy", "priority");
                suggestedOverride.addProperty("winner", conflict.winnerModId());
                JsonArray priorityMods = new JsonArray();
                for (String modId : conflict.contributingMods()) {
                    priorityMods.add(modId);
                }
                suggestedOverride.add("priorityMods", priorityMods);
                item.add("suggestedOverride", suggestedOverride);
                conflictEntries.add(item);
            }
            report.add("conflicts", conflictEntries);

            Files.writeString(
                reportPath,
                new GsonBuilder().setPrettyPrinting().create().toJson(report),
                StandardCharsets.UTF_8
            );
            return reportPath.toFile();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write InterMed VFS diagnostics report", e);
        }
    }

    private static JsonObject summaryJson(List<ResourceConflict> conflicts) {
        JsonObject summary = new JsonObject();
        summary.add("byResolution", countBy(conflicts, ResourceConflict::resolution));
        summary.add("byKind", countBy(conflicts, ResourceConflict::resourceKind));
        summary.add("byPolicySource", countBy(conflicts, ResourceConflict::policySource));
        return summary;
    }

    private static JsonObject countBy(List<ResourceConflict> conflicts,
                                      java.util.function.Function<ResourceConflict, String> classifier) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (ResourceConflict conflict : conflicts) {
            String key = classifier.apply(conflict);
            counts.merge(key, 1, Integer::sum);
        }
        JsonObject json = new JsonObject();
        counts.forEach(json::addProperty);
        return json;
    }

    private static void writeEntry(JarOutputStream output, String name, byte[] bytes) throws IOException {
        JarEntry entry = new JarEntry(name);
        output.putNextEntry(entry);
        output.write(bytes);
        output.closeEntry();
    }

    private static boolean isManagedResource(JarEntry entry) {
        if (entry == null || entry.isDirectory()) {
            return false;
        }
        String name = entry.getName();
        return name.startsWith("assets/") || name.startsWith("data/");
    }

    private static boolean hasAnyResources(NormalizedModMetadata metadata) {
        return metadata != null && (metadata.hasClientResources() || metadata.hasServerData());
    }

    private static boolean isJson(String path) {
        return path != null && path.endsWith(".json");
    }

    /**
     * Returns {@code true} for resource paths that are array-dominated and benefit
     * from CRDT LWW-Set merge semantics (ТЗ 3.5.6).
     */
    private static boolean isCrdtEligible(String path) {
        if (path == null) return false;
        return path.contains("/tags/")
            || path.contains("/loot_tables/")
            || path.contains("/advancements/")
            || path.contains("/predicates/")
            || path.contains("/worldgen/")
            || path.contains("/forge/biome_modifier/")
            || path.contains("/neoforge/biome_modifier/");
    }

    private static String classifyResourceKind(String path) {
        if (path == null || path.isBlank()) {
            return "resource";
        }
        if (path.contains("/tags/")) {
            return "tag";
        }
        if (path.contains("/loot_tables/")) {
            return "loot_table";
        }
        if (path.contains("/recipes/")) {
            return "recipe";
        }
        if (path.contains("/advancements/")) {
            return "advancement";
        }
        if (path.contains("/predicates/")) {
            return "predicate";
        }
        if (path.contains("/worldgen/")) {
            return "worldgen";
        }
        if (path.contains("/forge/biome_modifier/") || path.contains("/neoforge/biome_modifier/")) {
            return "biome_modifier";
        }
        if (path.startsWith("assets/") && path.contains("/lang/") && path.endsWith(".json")) {
            return "lang";
        }
        if (path.startsWith("assets/") && path.endsWith(".json")) {
            return "asset_json";
        }
        if (path.startsWith("data/") && path.endsWith(".json")) {
            return "data_json";
        }
        return "resource";
    }

    private static String sanitizeFileComponent(String value) {
        if (value == null || value.isBlank()) {
            return "pack";
        }
        return value.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "_");
    }

    private static int effectivePriority(ResourceContribution contribution, VirtualFileSystemOverrides.Rule overrideRule) {
        if (overrideRule == null) {
            return contribution.precedence();
        }
        if (overrideRule.winnerModId() != null && overrideRule.winnerModId().equals(contribution.modId())) {
            return Integer.MIN_VALUE;
        }
        int overrideIndex = overrideRule.priorityMods().indexOf(contribution.modId());
        if (overrideIndex >= 0) {
            return -1_000_000 + overrideIndex;
        }
        return contribution.precedence();
    }

    private static String fingerprint(List<NormalizedModMetadata> mods,
                                      RuntimeConfig config,
                                      VirtualFileSystemOverrides overrides) {
        StringBuilder descriptor = new StringBuilder()
            .append(config.cacheFingerprint())
            .append("|vfsOverrides=").append(overrides.fingerprint())
            .append("|vfsEnabled=").append(config.isVfsEnabled())
            .append("|vfsConflictPolicy=").append(config.getVfsConflictPolicy())
            .append("|vfsPriority=").append(String.join(",", config.getVfsPriorityOrder()));
        for (NormalizedModMetadata metadata : mods) {
            File sourceJar = metadata.sourceJar();
            descriptor.append("|")
                .append(metadata.id())
                .append("@")
                .append(sourceJar.getAbsolutePath())
                .append(":")
                .append(sourceJar.length())
                .append(":")
                .append(sourceJar.lastModified());
        }
        return AOTCacheManager.sha256(descriptor.toString());
    }

    private static String overlayFingerprint(List<NormalizedModMetadata> mods,
                                             RuntimeConfig config,
                                             Map<String, byte[]> overlayEntries,
                                             List<ResourceConflict> conflicts) {
        VirtualFileSystemOverrides overrides = VirtualFileSystemOverrides.load(config.getConfigDir());
        StringBuilder descriptor = new StringBuilder(fingerprint(mods, config, overrides));
        for (Map.Entry<String, byte[]> entry : overlayEntries.entrySet()) {
            descriptor.append("|overlay:").append(entry.getKey()).append(":").append(AOTCacheManager.sha256(entry.getValue()));
        }
        for (ResourceConflict conflict : conflicts) {
            descriptor.append("|conflict:")
                .append(conflict.path())
                .append(":")
                .append(conflict.winnerModId())
                .append(":")
                .append(conflict.resolution());
        }
        return AOTCacheManager.sha256(descriptor.toString());
    }

    private static LinkedHashMap<String, List<ResourceLocator>> buildResourceIndex(
        Map<String, List<ResourceContribution>> contributions,
        LinkedHashMap<String, byte[]> overlayEntries,
        File overlayPack) {
        LinkedHashMap<String, List<ResourceLocator>> index = new LinkedHashMap<>();
        for (Map.Entry<String, List<ResourceContribution>> entry : contributions.entrySet()) {
            List<ResourceLocator> locators = new ArrayList<>();
            if (overlayPack != null && overlayEntries.containsKey(entry.getKey())) {
                locators.add(new ResourceLocator(overlayPack, entry.getKey(), "intermed-vfs"));
            }
            entry.getValue().stream()
                .sorted(Comparator.comparingInt(ResourceContribution::precedence))
                .forEach(contribution -> locators.add(new ResourceLocator(
                    contribution.sourceJar(),
                    entry.getKey(),
                    contribution.modId()
                )));
            index.put(entry.getKey(), List.copyOf(locators));
        }
        return index;
    }

    public record MountPlan(List<File> mountablePacks,
                            File overlayPack,
                            List<ResourceConflict> conflicts,
                            int mergedResourceCount,
                            Map<String, List<ResourceLocator>> resourceIndex,
                            File diagnosticsReport) {
        public MountPlan {
            mountablePacks = List.copyOf(mountablePacks);
            conflicts = List.copyOf(conflicts);
            resourceIndex = Map.copyOf(resourceIndex);
        }

        public boolean hasOverlayPack() {
            return overlayPack != null;
        }

        public boolean hasDiagnosticsReport() {
            return diagnosticsReport != null;
        }

        public List<URL> resolve(String resourcePath) {
            List<ResourceLocator> locators = resourceIndex.get(resourcePath);
            if (locators == null || locators.isEmpty()) {
                return List.of();
            }
            ArrayList<URL> urls = new ArrayList<>(locators.size());
            for (ResourceLocator locator : locators) {
                URL url = locator.toUrl();
                if (url != null) {
                    urls.add(url);
                }
            }
            return List.copyOf(urls);
        }
    }

    public record ResourceConflict(String path,
                                   String resourceKind,
                                   List<String> contributingMods,
                                   String winnerModId,
                                   String resolution,
                                   boolean jsonMerged,
                                   String policySource) {
        public ResourceConflict {
            contributingMods = List.copyOf(contributingMods);
        }
    }

    private record ResourceContribution(String modId, File sourceJar, int precedence, byte[] bytes) {}

    private record Resolution(byte[] bytes, ResourceConflict conflict) {}

    public record ResourceLocator(File sourcePack, String resourcePath, String ownerModId) {
        URL toUrl() {
            try {
                return new URL("jar:" + sourcePack.toURI().toURL() + "!/" + resourcePath);
            } catch (MalformedURLException e) {
                return null;
            }
        }
    }
}
