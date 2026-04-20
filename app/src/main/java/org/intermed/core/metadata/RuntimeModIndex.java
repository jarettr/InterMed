package org.intermed.core.metadata;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared runtime index of normalized manifests exposed to bridges and diagnostics.
 */
public final class RuntimeModIndex {

    private static final ConcurrentHashMap<String, NormalizedModMetadata> MODS = new ConcurrentHashMap<>();

    private RuntimeModIndex() {}

    public static void register(NormalizedModMetadata metadata) {
        if (metadata != null) {
            MODS.put(metadata.id(), metadata);
        }
    }

    public static void registerAll(Collection<NormalizedModMetadata> metadata) {
        clear();
        if (metadata != null) {
            metadata.forEach(RuntimeModIndex::register);
        }
    }

    public static Optional<NormalizedModMetadata> get(String modId) {
        if (modId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(MODS.get(modId));
    }

    public static boolean isLoaded(String modId) {
        return modId != null && MODS.containsKey(modId);
    }

    public static Collection<NormalizedModMetadata> allMods() {
        return Collections.unmodifiableCollection(MODS.values());
    }

    public static List<String> entrypoints(String modId, String key) {
        return get(modId)
            .map(metadata -> metadata.entrypoints(key))
            .orElseGet(List::of);
    }

    public static void clear() {
        MODS.clear();
    }
}
