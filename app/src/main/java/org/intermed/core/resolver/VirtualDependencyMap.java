package org.intermed.core.resolver;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Registry of ecosystem-specific dependency IDs → InterMed bridge IDs
 * (ТЗ 3.2.1, Requirement 2 — Dummy Constraints / Virtual Dependencies).
 *
 * <h3>Motivation</h3>
 * A Fabric mod declares {@code "depends": {"fabric-api": ">=0.90"}} in its
 * manifest.  When that mod is loaded by InterMed, the real {@code fabric-api}
 * JAR is not present — instead, InterMed provides an equivalent bridge module.
 *
 * Before feeding manifests to PubGrub, every ecosystem-specific dependency ID
 * is transparently substituted so that:
 * <ul>
 *   <li>PubGrub sees {@code intermed-fabric-bridge@1.0.0} where a Fabric mod
 *       expected {@code fabric-api}.</li>
 *   <li>The resulting ClassLoader parent edge points at the bridge loader,
 *       giving the mod access to the platform API surface it needs.</li>
 * </ul>
 *
 * <h3>Adding substitutions</h3>
 * Call {@link #register(String, String)} at boot time to add custom mappings,
 * or use the {@link #SUBSTITUTIONS} map directly for compile-time entries.
 */
public final class VirtualDependencyMap {

    /**
     * Canonical substitution table.  Keys are lower-cased for case-insensitive
     * lookup.  Values are the InterMed bridge IDs that satisfy the dependency.
     */
    private static final Map<String, String> SUBSTITUTIONS;
    private static final Map<String, String> BRIDGE_BASELINES;
    private static final Map<String, String> DEPENDENCY_ALIASES;
    private static final Map<String, Set<String>> BRIDGE_PROVIDER_IDS;

    static {
        Map<String, String> m = new HashMap<>();
        Map<String, String> baselines = new HashMap<>();
        Map<String, String> aliases = new HashMap<>();
        Map<String, Set<String>> providers = new HashMap<>();

        // ── Fabric ecosystem ────────────────────────────────────────────────
        m.put("fabric-api",        "intermed-fabric-bridge");
        m.put("fabric",            "intermed-fabric-bridge");
        m.put("fabricloader",      "intermed-fabric-bridge");
        m.put("fabric-loader",     "intermed-fabric-bridge");
        m.put("fabric-language-kotlin", "intermed-fabric-bridge");
        baselines.put("intermed-fabric-bridge", "0.90.0");
        providers.put("intermed-fabric-bridge", Set.of("fabric-api"));

        // ── Cross-loader/library aliases ────────────────────────────────────
        // Some Fabric mods, including YUNG's 1.20.x line, depend on the legacy
        // Cloth Config id while the published library jar advertises cloth-config.
        aliases.put("cloth-config2", "cloth-config");

        // ── Forge ecosystem ─────────────────────────────────────────────────
        m.put("forge",             "intermed-forge-bridge");
        m.put("minecraftforge",    "intermed-forge-bridge");
        m.put("minecraft-forge",   "intermed-forge-bridge");
        m.put("neoforge",          "intermed-neoforge-bridge");
        m.put("neoforged",         "intermed-neoforge-bridge");
        m.put("neo-forge",         "intermed-neoforge-bridge");
        m.put("net.neoforged",     "intermed-neoforge-bridge");
        baselines.put("intermed-forge-bridge", "47.2.0");
        baselines.put("intermed-neoforge-bridge", "21.0.0");

        // ── Platform (always satisfied by InterMed core) ───────────────────
        m.put("minecraft",         "intermed-minecraft-runtime");
        m.put("java",              "intermed-java-runtime");
        baselines.put("intermed-minecraft-runtime", "1.20.1");
        baselines.put("intermed-java-runtime", Runtime.version().feature() + ".0.0");

        SUBSTITUTIONS = Collections.unmodifiableMap(m);
        BRIDGE_BASELINES = Collections.unmodifiableMap(baselines);
        DEPENDENCY_ALIASES = Collections.unmodifiableMap(aliases);
        BRIDGE_PROVIDER_IDS = Collections.unmodifiableMap(providers);
    }

    private VirtualDependencyMap() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the InterMed bridge ID that should satisfy {@code dependencyId},
     * or the original ID unchanged if no substitution is registered.
     *
     * <p>Matching is case-insensitive.
     *
     * @param dependencyId The dependency ID as declared in the mod manifest.
     * @return The resolved bridge ID, or {@code dependencyId} if not virtual.
     */
    public static String substitute(String dependencyId) {
        if (dependencyId == null) return null;
        String canonical = canonicalize(dependencyId);
        return SUBSTITUTIONS.getOrDefault(canonical.toLowerCase(), canonical);
    }

    /**
     * Returns the canonical package id for known ecosystem aliases that refer to
     * the same concrete mod jar. Unlike virtual substitutions, aliases still
     * resolve to a real user-provided dependency node.
     */
    public static String canonicalize(String dependencyId) {
        if (dependencyId == null) return null;
        String trimmed = dependencyId.trim();
        if (trimmed.isEmpty()) return trimmed;
        return DEPENDENCY_ALIASES.getOrDefault(trimmed.toLowerCase(), trimmed);
    }

    /**
     * Returns the substitution target for {@code dependencyId} and validates that
     * every virtual dependency resolves to a non-empty bridge id with a defined
     * compatibility baseline.
     */
    public static String substituteChecked(String dependencyId) {
        String substituted = substitute(dependencyId);
        if (!isVirtual(dependencyId)) {
            return substituted;
        }
        validateResolvedBridge(dependencyId, substituted);
        return substituted;
    }

    /**
     * Returns the effective constraint that should be applied after virtual
     * dependency substitution.
     *
     * <p>Bridge modules advertise a compatibility baseline version so the
     * resolver can keep meaningful upstream constraints instead of widening
     * everything to {@code "*"}.
     */
    public static String effectiveConstraint(String dependencyId, String declaredConstraint) {
        String normalizedConstraint = (declaredConstraint == null || declaredConstraint.isBlank())
            ? "*"
            : declaredConstraint.trim();
        if (!isVirtual(dependencyId)) {
            return normalizedConstraint;
        }
        String bridgeId = substituteChecked(dependencyId);
        return bridgeCompatibilityVersionForBridge(bridgeId).equals("*")
            ? "*"
            : normalizedConstraint;
    }

    /**
     * Returns {@code true} if {@code dependencyId} will be replaced by a
     * bridge module during resolution.
     */
    public static boolean isVirtual(String dependencyId) {
        String canonical = canonicalize(dependencyId);
        return canonical != null
            && SUBSTITUTIONS.containsKey(canonical.toLowerCase());
    }

    /**
     * Returns the set of all bridge module IDs that appear as substitution
     * targets.  These must be injected as synthetic nodes before PubGrub runs.
     */
    public static java.util.Set<String> allBridgeIds() {
        return new java.util.HashSet<>(SUBSTITUTIONS.values());
    }

    /**
     * Returns the full substitution map (unmodifiable, lower-cased keys).
     * Useful for diagnostic logging.
     */
    public static Map<String, String> getAllSubstitutions() {
        return SUBSTITUTIONS;
    }

    public static String bridgeCompatibilityVersionForBridge(String bridgeId) {
        return bridgeCompatibilityVersionForBridge(bridgeId, Map.of());
    }

    /**
     * Resolves the effective compatibility version for a synthetic bridge,
     * preferring explicit operator overrides and then concrete provider modules
     * discovered in the current runtime graph.
     *
     * <p>This keeps virtual dependency resolution aligned with the real
     * platform/API version available to mods instead of pinning bridge stubs to
     * a static compile-time baseline.
     */
    public static String bridgeCompatibilityVersionForBridge(String bridgeId,
                                                             Map<String, String> discoveredModuleVersions) {
        if (bridgeId == null || bridgeId.isBlank()) {
            return "1.0.0";
        }
        String explicitOverride = explicitBridgeVersionOverride(bridgeId);
        if (explicitOverride != null && !explicitOverride.isBlank()) {
            return explicitOverride;
        }
        String inferredVersion = inferDiscoveredBridgeVersion(bridgeId, discoveredModuleVersions);
        if (inferredVersion != null && !inferredVersion.isBlank()) {
            return inferredVersion;
        }
        return BRIDGE_BASELINES.getOrDefault(bridgeId, "1.0.0");
    }

    private static String explicitBridgeVersionOverride(String bridgeId) {
        return switch (bridgeId) {
            case "intermed-fabric-bridge" -> System.getProperty("intermed.compat.fabricBridgeVersion");
            case "intermed-forge-bridge" -> System.getProperty("intermed.compat.forgeBridgeVersion");
            case "intermed-neoforge-bridge" -> System.getProperty("intermed.compat.neoforgeBridgeVersion");
            case "intermed-minecraft-runtime" -> System.getProperty("intermed.compat.minecraftVersion");
            case "intermed-java-runtime" -> System.getProperty("intermed.compat.javaVersion");
            default -> null;
        };
    }

    private static String inferDiscoveredBridgeVersion(String bridgeId,
                                                       Map<String, String> discoveredModuleVersions) {
        if (discoveredModuleVersions == null || discoveredModuleVersions.isEmpty()) {
            return null;
        }
        Set<String> providerIds = BRIDGE_PROVIDER_IDS.get(bridgeId);
        if (providerIds == null || providerIds.isEmpty()) {
            return null;
        }

        String chosen = null;
        for (String providerId : providerIds) {
            String providerVersion = discoveredModuleVersions.get(providerId);
            if (providerVersion == null || providerVersion.isBlank()) {
                continue;
            }
            String normalized = normalizeProviderVersion(bridgeId, providerVersion);
            if (normalized.isBlank()) {
                continue;
            }
            if (chosen == null
                || SemVerConstraint.SemVer.parse(normalized)
                    .compareTo(SemVerConstraint.SemVer.parse(chosen)) > 0) {
                chosen = normalized;
            }
        }
        return chosen;
    }

    private static String normalizeProviderVersion(String bridgeId, String providerVersion) {
        String trimmed = providerVersion == null ? "" : providerVersion.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if ("intermed-fabric-bridge".equals(bridgeId)) {
            return trimmed;
        }
        return trimmed;
    }

    private static void validateResolvedBridge(String dependencyId, String bridgeId) {
        String normalizedDependencyId = dependencyId == null ? "" : dependencyId.trim();
        if (bridgeId == null || bridgeId.isBlank()) {
            throw new IllegalStateException("Virtual dependency '" + normalizedDependencyId
                + "' did not resolve to a bridge module id");
        }
        if (bridgeId.equalsIgnoreCase(normalizedDependencyId)) {
            throw new IllegalStateException("Virtual dependency '" + normalizedDependencyId
                + "' resolved to itself instead of an InterMed bridge");
        }
        String baseline = bridgeCompatibilityVersionForBridge(bridgeId);
        if (baseline == null || baseline.isBlank()) {
            throw new IllegalStateException("Bridge module '" + bridgeId
                + "' is missing a compatibility baseline for virtual dependency '" + normalizedDependencyId + "'");
        }
    }
}
