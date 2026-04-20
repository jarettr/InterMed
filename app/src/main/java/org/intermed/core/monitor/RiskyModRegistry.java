package org.intermed.core.monitor;

import org.intermed.core.classloading.TransformationContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Collects risky transformation signals so runtime diagnostics can identify
 * mods that relied on degraded remap heuristics.
 */
public final class RiskyModRegistry {

    private static final Map<String, Set<String>> REASONS_BY_MOD = new ConcurrentHashMap<>();
    private static final Map<String, Set<String>> REASONS_BY_CLASS = new ConcurrentHashMap<>();

    private RiskyModRegistry() {}

    public static void markCurrentClassRisky(String reason) {
        markRisky(
            TransformationContext.currentModIdOr("unknown"),
            TransformationContext.currentClassNameOr("unknown"),
            reason
        );
    }

    public static void markRisky(String modId, String className, String reason) {
        if (reason == null || reason.isBlank()) {
            return;
        }
        String normalizedModId = (modId == null || modId.isBlank()) ? "unknown" : modId;
        String normalizedClassName = (className == null || className.isBlank()) ? "unknown" : className;

        REASONS_BY_MOD.computeIfAbsent(normalizedModId, ignored -> ConcurrentHashMap.newKeySet()).add(reason);
        REASONS_BY_CLASS.computeIfAbsent(normalizedClassName, ignored -> ConcurrentHashMap.newKeySet()).add(reason);
    }

    public static boolean isModRisky(String modId) {
        return modId != null && !reasonsForMod(modId).isEmpty();
    }

    public static List<String> reasonsForMod(String modId) {
        if (modId == null) {
            return List.of();
        }
        return immutableCopy(REASONS_BY_MOD.get(modId));
    }

    public static List<String> reasonsForClass(String className) {
        if (className == null) {
            return List.of();
        }
        return immutableCopy(REASONS_BY_CLASS.get(className));
    }

    public static void resetForTests() {
        REASONS_BY_MOD.clear();
        REASONS_BY_CLASS.clear();
    }

    private static List<String> immutableCopy(Set<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return List.of();
        }
        return List.copyOf(new ArrayList<>(new LinkedHashSet<>(reasons)));
    }
}
