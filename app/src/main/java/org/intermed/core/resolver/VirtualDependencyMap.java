package org.intermed.core.resolver;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

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

    static {
        Map<String, String> m = new HashMap<>();
        Map<String, String> baselines = new HashMap<>();

        // ── Fabric ecosystem ────────────────────────────────────────────────
        m.put("fabric-api",        "intermed-fabric-bridge");
        m.put("fabric",            "intermed-fabric-bridge");
        m.put("fabricloader",      "intermed-fabric-bridge");
        m.put("fabric-loader",     "intermed-fabric-bridge");
        m.put("fabric-language-kotlin", "intermed-fabric-bridge");
        baselines.put("intermed-fabric-bridge", "0.90.0");

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
        return SUBSTITUTIONS.getOrDefault(dependencyId.toLowerCase(), dependencyId);
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
        return dependencyId != null
            && SUBSTITUTIONS.containsKey(dependencyId.toLowerCase());
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
        if (bridgeId == null || bridgeId.isBlank()) {
            return "1.0.0";
        }
        return switch (bridgeId) {
            case "intermed-fabric-bridge" ->
                System.getProperty("intermed.compat.fabricBridgeVersion",
                    BRIDGE_BASELINES.getOrDefault(bridgeId, "0.90.0"));
            case "intermed-forge-bridge" ->
                System.getProperty("intermed.compat.forgeBridgeVersion",
                    BRIDGE_BASELINES.getOrDefault(bridgeId, "47.2.0"));
            case "intermed-neoforge-bridge" ->
                System.getProperty("intermed.compat.neoforgeBridgeVersion",
                    BRIDGE_BASELINES.getOrDefault(bridgeId, "21.0.0"));
            case "intermed-minecraft-runtime" ->
                System.getProperty("intermed.compat.minecraftVersion",
                    BRIDGE_BASELINES.getOrDefault(bridgeId, "1.20.1"));
            case "intermed-java-runtime" ->
                System.getProperty("intermed.compat.javaVersion",
                    BRIDGE_BASELINES.getOrDefault(bridgeId, Runtime.version().feature() + ".0.0"));
            default -> BRIDGE_BASELINES.getOrDefault(bridgeId, "1.0.0");
        };
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
