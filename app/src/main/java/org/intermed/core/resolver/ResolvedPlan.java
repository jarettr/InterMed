package org.intermed.core.resolver;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The output produced by a successful PubGrub resolution pass
 * (ТЗ 3.2.1, Requirement 2).
 *
 * <h3>Role in the ClassLoader DAG</h3>
 * This record is the bridge between PubGrub and the ClassLoader graph builder
 * in {@link org.intermed.core.lifecycle.LifecycleManager}.
 *
 * <ul>
 *   <li>{@link #loadOrder} replaces the separate Kahn's topological sort:
 *       every package appears after all its dependencies, so iterating
 *       {@code loadOrder} in order guarantees that a mod's parent ClassLoaders
 *       are already constructed before the mod itself.</li>
 *   <li>{@link #dependencyEdges} (after {@link VirtualDependencyMap} substitution)
 *       maps each package to the direct dependencies whose ClassLoaders must
 *       become parent nodes in the DAG.  This is what the spec means by
 *       "граф зависимостей, который будет отображён в структуру ClassLoader'ов".</li>
 *   <li>{@link #resolvedVersions} records the exact version selected for each
 *       package.  Used for cache-key generation and diagnostic output.</li>
 * </ul>
 *
 * @param resolvedVersions        {@code packageId → version} after conflict resolution.
 *                                Virtual dependencies are stored under their bridge IDs
 *                                (e.g. {@code "intermed-fabric-bridge"}).
 * @param dependencyEdges         {@code packageId → set of direct dependency IDs}
 *                                after {@link VirtualDependencyMap} substitution.
 *                                These edges drive ClassLoader parent assignment.
 * @param loadOrder               Topologically sorted list of package IDs: a package
 *                                always appears strictly after all its transitive
 *                                dependencies.
 * @param softMissingDependencies Set of dependency IDs that were declared by some
 *                                package but could not be satisfied because no
 *                                candidate existed.  These packages were gracefully
 *                                degraded (skipped) rather than causing resolution to
 *                                fail hard.  Callers should log a warning for each
 *                                entry and skip building a ClassLoader edge.
 * @param moduleKinds             Canonical runtime kind for each resolved node.
 *                                Bridge/runtime modules are explicitly typed here
 *                                instead of being inferred from ad-hoc stub logic.
 * @param diagnostics             Structured diagnostics produced during resolution.
 */
public record ResolvedPlan(
    Map<String, String>      resolvedVersions,
    Map<String, Set<String>> dependencyEdges,
    List<String>             loadOrder,
    Set<String>              softMissingDependencies,
    Map<String, RuntimeModuleKind> moduleKinds,
    List<ResolutionDiagnostic> diagnostics
) {

    /** An empty plan that signals a failed or skipped resolution. */
    public static final ResolvedPlan EMPTY = new ResolvedPlan(
        Collections.emptyMap(),
        Collections.emptyMap(),
        Collections.emptyList(),
        Collections.emptySet(),
        Collections.emptyMap(),
        Collections.emptyList()
    );

    /** Returns {@code true} if this plan contains at least one resolved package. */
    public boolean isResolved() {
        return !resolvedVersions.isEmpty();
    }

    /**
     * Returns the resolved version of {@code packageId}, or {@code null} if
     * this package was not part of the resolution.
     */
    public String versionOf(String packageId) {
        return resolvedVersions.get(packageId);
    }

    /**
     * Returns the direct dependency IDs of {@code packageId} as determined by
     * the resolver (post-substitution).  Returns an empty set for unknown IDs.
     */
    public Set<String> depsOf(String packageId) {
        return dependencyEdges.getOrDefault(packageId, Collections.emptySet());
    }

    /**
     * Returns {@code true} if {@code dependencyId} was declared by some package
     * but could not be satisfied and was soft-degraded instead of failing hard.
     */
    public boolean isSoftMissing(String dependencyId) {
        return softMissingDependencies.contains(dependencyId);
    }

    /**
     * Returns the runtime kind of {@code moduleId}, defaulting to
     * {@link RuntimeModuleKind#MOD} for unknown nodes.
     */
    public RuntimeModuleKind moduleKindOf(String moduleId) {
        return moduleKinds.getOrDefault(moduleId, RuntimeModuleKind.MOD);
    }

    /**
     * Returns {@code true} if {@code moduleId} resolves to a built-in bridge or
     * runtime node rather than a user-supplied mod.
     */
    public boolean isBuiltInRuntime(String moduleId) {
        return moduleKindOf(moduleId).isBuiltInRuntime();
    }
}
