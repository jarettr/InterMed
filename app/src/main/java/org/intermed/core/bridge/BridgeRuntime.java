package org.intermed.core.bridge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared runtime registry for bridge-visible state such as initialized entrypoints.
 */
public final class BridgeRuntime {

    private static final Map<String, List<LoadedEntrypoint>> ENTRYPOINTS_BY_KEY = new ConcurrentHashMap<>();

    private BridgeRuntime() {}

    public static void registerEntrypoint(String modId, String key, String definition, Object instance) {
        if (modId == null || key == null || definition == null || instance == null) {
            return;
        }
        ENTRYPOINTS_BY_KEY.compute(key, (ignored, current) -> {
            List<LoadedEntrypoint> updated = current == null ? new ArrayList<>() : new ArrayList<>(current);
            boolean alreadyRegistered = updated.stream().anyMatch(entry ->
                entry.modId().equals(modId) && entry.definition().equals(definition));
            if (!alreadyRegistered) {
                updated.add(new LoadedEntrypoint(modId, definition, instance));
            }
            return List.copyOf(updated);
        });
    }

    public static List<LoadedEntrypoint> getEntrypoints(String key) {
        return ENTRYPOINTS_BY_KEY.getOrDefault(key, List.of());
    }

    public static void reset() {
        ENTRYPOINTS_BY_KEY.clear();
    }

    public record LoadedEntrypoint(String modId, String definition, Object instance) {}
}
