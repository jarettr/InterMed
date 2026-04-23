package org.intermed.core.metadata;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared runtime index of normalized manifests exposed to bridges and diagnostics.
 */
public final class RuntimeModIndex {
    private static final String SNAPSHOT_PROPERTY = "intermed.runtimeModIndex.snapshot.v1";

    private static final ConcurrentHashMap<String, NormalizedModMetadata> LOADED_MODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, NormalizedModMetadata> DISCOVERED_MODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> LOAD_FAILURES = new ConcurrentHashMap<>();

    private RuntimeModIndex() {}

    public static void register(NormalizedModMetadata metadata) {
        if (metadata != null) {
            LOADED_MODS.put(metadata.id(), metadata);
            DISCOVERED_MODS.put(metadata.id(), metadata);
            LOAD_FAILURES.remove(metadata.id());
            publishSnapshot();
        }
    }

    public static void registerAll(Collection<NormalizedModMetadata> metadata) {
        LOADED_MODS.clear();
        if (metadata != null) {
            metadata.forEach(item -> {
                if (item != null) {
                    LOADED_MODS.put(item.id(), item);
                    DISCOVERED_MODS.put(item.id(), item);
                    LOAD_FAILURES.remove(item.id());
                }
            });
        }
        publishSnapshot();
    }

    public static void registerDiscovered(NormalizedModMetadata metadata) {
        if (metadata != null) {
            DISCOVERED_MODS.put(metadata.id(), metadata);
            publishSnapshot();
        }
    }

    public static void registerDiscoveredAll(Collection<NormalizedModMetadata> metadata) {
        DISCOVERED_MODS.clear();
        LOAD_FAILURES.clear();
        if (metadata != null) {
            metadata.forEach(item -> {
                if (item != null) {
                    DISCOVERED_MODS.put(item.id(), item);
                }
            });
        }
        publishSnapshot();
    }

    public static Optional<NormalizedModMetadata> get(String modId) {
        if (modId == null) {
            return Optional.empty();
        }
        NormalizedModMetadata loaded = LOADED_MODS.get(modId);
        if (loaded != null) {
            return Optional.of(loaded);
        }
        return snapshotMods().stream()
            .filter(metadata -> modId.equals(metadata.id()))
            .findFirst();
    }

    public static boolean isLoaded(String modId) {
        if (modId == null) {
            return false;
        }
        if (LOADED_MODS.containsKey(modId)) {
            return true;
        }
        return snapshotMods().stream()
            .anyMatch(metadata -> modId.equals(metadata.id()) && snapshotLoaded(metadata.id()));
    }

    public static boolean isDiscovered(String modId) {
        if (modId == null) {
            return false;
        }
        if (DISCOVERED_MODS.containsKey(modId)) {
            return true;
        }
        return snapshotMods().stream()
            .anyMatch(metadata -> modId.equals(metadata.id()));
    }

    public static Collection<NormalizedModMetadata> allMods() {
        return Collections.unmodifiableCollection(LOADED_MODS.values());
    }

    public static Collection<NormalizedModMetadata> discoveredMods() {
        return Collections.unmodifiableCollection(DISCOVERED_MODS.values());
    }

    public static void markLoadFailure(String modId, String reason) {
        if (modId == null || modId.isBlank() || reason == null || reason.isBlank()) {
            return;
        }
        if (LOADED_MODS.containsKey(modId)) {
            LOAD_FAILURES.remove(modId);
        } else {
            LOAD_FAILURES.put(modId, reason);
        }
        publishSnapshot();
    }

    public static Optional<String> loadFailure(String modId) {
        if (modId == null || modId.isBlank()) {
            return Optional.empty();
        }
        String failure = LOAD_FAILURES.get(modId);
        if (failure != null && !failure.isBlank()) {
            return Optional.of(failure);
        }
        return snapshotFailure(modId);
    }

    public static Collection<NormalizedModMetadata> visibleModsForUi() {
        if (!LOADED_MODS.isEmpty()) {
            LinkedHashMap<String, NormalizedModMetadata> visible = new LinkedHashMap<>();
            LOADED_MODS.values().forEach(metadata -> visible.put(metadata.id(), metadata));
            LOAD_FAILURES.keySet().forEach(modId -> {
                NormalizedModMetadata discovered = DISCOVERED_MODS.get(modId);
                if (discovered != null) {
                    visible.putIfAbsent(modId, discovered);
                }
            });
            if (!visible.isEmpty()) {
                return Collections.unmodifiableList(new ArrayList<>(visible.values()));
            }
        }
        Collection<NormalizedModMetadata> discovered = discoveredMods();
        if (!discovered.isEmpty()) {
            return discovered;
        }
        List<NormalizedModMetadata> snapshot = snapshotMods();
        if (snapshot.isEmpty()) {
            return List.of();
        }
        return Collections.unmodifiableList(snapshot);
    }

    public static List<String> entrypoints(String modId, String key) {
        return get(modId)
            .map(metadata -> metadata.entrypoints(key))
            .orElseGet(List::of);
    }

    public static void clear() {
        LOADED_MODS.clear();
        DISCOVERED_MODS.clear();
        LOAD_FAILURES.clear();
        System.clearProperty(SNAPSHOT_PROPERTY);
    }

    private static void publishSnapshot() {
        JsonArray payload = new JsonArray();
        for (NormalizedModMetadata metadata : DISCOVERED_MODS.values()) {
            if (metadata == null) {
                continue;
            }
            JsonObject entry = new JsonObject();
            entry.addProperty("id", metadata.id());
            entry.addProperty("name", metadata.name());
            entry.addProperty("version", metadata.version());
            entry.addProperty("platform", metadata.platform().name());
            entry.addProperty("loaded", LOADED_MODS.containsKey(metadata.id()));
            String failure = LOAD_FAILURES.get(metadata.id());
            if (failure != null && !failure.isBlank()) {
                entry.addProperty("failure", failure);
            }
            if (metadata.sourceJar() != null) {
                entry.addProperty("source", metadata.sourceJar().getName());
            }
            payload.add(entry);
        }
        System.setProperty(SNAPSHOT_PROPERTY, payload.toString());
    }

    private static List<NormalizedModMetadata> snapshotMods() {
        String raw = System.getProperty(SNAPSHOT_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonElement root = JsonParser.parseString(raw);
            if (!root.isJsonArray()) {
                return List.of();
            }
            ArrayList<NormalizedModMetadata> result = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                if (!entry.has("id") || !entry.has("platform")) {
                    continue;
                }
                String id = entry.get("id").getAsString();
                JsonObject manifest = new JsonObject();
                manifest.addProperty("id", id);
                manifest.addProperty("name", entry.has("name") ? entry.get("name").getAsString() : id);
                manifest.addProperty("version", entry.has("version") ? entry.get("version").getAsString() : "0.0.0");
                File sourceJar = entry.has("source") ? new File(entry.get("source").getAsString()) : null;
                result.add(new NormalizedModMetadata(
                    id,
                    manifest.get("version").getAsString(),
                    sourceJar,
                    ModPlatform.valueOf(entry.get("platform").getAsString()),
                    manifest,
                    Map.of()
                ));
            }
            return List.copyOf(result);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static boolean snapshotLoaded(String modId) {
        String raw = System.getProperty(SNAPSHOT_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            JsonElement root = JsonParser.parseString(raw);
            if (!root.isJsonArray()) {
                return false;
            }
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                if (modId.equals(entry.has("id") ? entry.get("id").getAsString() : null)) {
                    return entry.has("loaded") && entry.get("loaded").getAsBoolean();
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static Optional<String> snapshotFailure(String modId) {
        String raw = System.getProperty(SNAPSHOT_PROPERTY);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonElement root = JsonParser.parseString(raw);
            if (!root.isJsonArray()) {
                return Optional.empty();
            }
            for (JsonElement element : root.getAsJsonArray()) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject entry = element.getAsJsonObject();
                if (modId.equals(entry.has("id") ? entry.get("id").getAsString() : null)
                    && entry.has("failure")) {
                    String failure = entry.get("failure").getAsString();
                    if (!failure.isBlank()) {
                        return Optional.of(failure);
                    }
                }
            }
        } catch (Exception ignored) {
            return Optional.empty();
        }
        return Optional.empty();
    }
}
