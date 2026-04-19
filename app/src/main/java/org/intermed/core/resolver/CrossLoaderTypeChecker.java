package org.intermed.core.resolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cross-loader type-safety check (ТЗ 3.2.1, Requirement 2 — cross-loader compatibility).
 *
 * <p>After PubGrub resolves a concrete version for every mod, each declared
 * dependency edge carries a version constraint (e.g. {@code >=1.2.0}).  This
 * class walks every edge in the {@link ResolvedPlan} and verifies that the
 * resolved version of the dependency actually satisfies the constraint declared
 * by its dependant.
 *
 * <h3>Why this is needed</h3>
 * PubGrub guarantees that <em>all constraints it was given</em> are satisfied.
 * However, virtual-dependency substitution can widen or alter constraints, and
 * the Kahn's-sort fallback path bypasses PubGrub entirely.  Running this check
 * after the plan is finalised provides an explicit safety net.
 *
 * <h3>Failure modes</h3>
 * <ul>
 *   <li>{@link Severity#HARD} — the resolved version definitively violates the
 *       declared constraint.  The DAG build will be aborted.</li>
 *   <li>{@link Severity#SOFT} — the constraint cannot be evaluated (e.g. the
 *       resolved version is missing from the plan, which can happen for bridge
 *       stubs).  A warning is printed but loading continues.</li>
 * </ul>
 */
public final class CrossLoaderTypeChecker {

    public enum Severity { HARD, SOFT }

    /**
     * A single failed compatibility check.
     *
     * @param dependant   The mod that declared the dependency.
     * @param dependency  The mod that was depended upon.
     * @param constraint  The raw version constraint declared by the dependant.
     * @param resolved    The actual resolved version of the dependency
     *                    (may be {@code null} if the plan didn't include it).
     * @param severity    {@link Severity#HARD} if the constraint is violated,
     *                    {@link Severity#SOFT} if it couldn't be evaluated.
     */
    public record Violation(
        String   dependant,
        String   dependency,
        String   constraint,
        String   resolved,
        Severity severity
    ) {
        @Override
        public String toString() {
            return String.format(
                "[%s] '%s' requires '%s %s' but resolved version is '%s'",
                severity, dependant, dependency, constraint,
                resolved != null ? resolved : "<not resolved>"
            );
        }
    }

    private CrossLoaderTypeChecker() {}

    /**
     * Validates all dependency edges in {@code plan} against the version
     * constraints recorded in {@code dependencyConstraints}.
     *
     * <p>{@code dependencyConstraints} maps
     * {@code "dependantId:dependencyId" → rawConstraint}.
     * Build this map from the original mod manifests before calling.
     *
     * @param plan                  the resolved plan to validate
     * @param dependencyConstraints edge constraints keyed as
     *                              {@code "dependantId:dependencyId"}
     * @return list of violations — empty if every edge is satisfied
     */
    public static List<Violation> validate(
            ResolvedPlan plan,
            Map<String, String> dependencyConstraints) {

        List<Violation> violations = new ArrayList<>();

        for (Map.Entry<String, java.util.Set<String>> entry : plan.dependencyEdges().entrySet()) {
            String dependantId = entry.getKey();
            for (String depId : entry.getValue()) {
                String key = dependantId + ":" + depId;
                String rawConstraint = dependencyConstraints.get(key);
                if (rawConstraint == null || rawConstraint.equals("*") || rawConstraint.equals("any")) {
                    continue; // wildcard — nothing to check
                }

                String resolvedVersion = plan.resolvedVersions().get(depId);
                if (resolvedVersion == null) {
                    // dep present in edges but absent from resolvedVersions — soft
                    violations.add(new Violation(dependantId, depId, rawConstraint, null, Severity.SOFT));
                    continue;
                }

                SemVerConstraint constraint = SemVerConstraint.parse(rawConstraint);
                if (!constraint.matches(resolvedVersion)) {
                    violations.add(new Violation(
                        dependantId, depId, rawConstraint, resolvedVersion, Severity.HARD));
                }
            }
        }

        return violations;
    }

    /**
     * Convenience method: logs all violations and throws
     * {@link IllegalStateException} if any {@link Severity#HARD} violation
     * exists.
     *
     * @throws IllegalStateException on any HARD violation
     */
    public static void validateAndThrow(
            ResolvedPlan plan,
            Map<String, String> dependencyConstraints) {

        List<Violation> violations = validate(plan, dependencyConstraints);
        if (violations.isEmpty()) return;

        boolean hasHard = false;
        for (Violation v : violations) {
            if (v.severity() == Severity.HARD) {
                System.err.printf("\033[1;31m[CrossLoaderTypeChecker] HARD violation: %s\033[0m%n", v);
                hasHard = true;
            } else {
                System.err.printf("\033[1;33m[CrossLoaderTypeChecker] SOFT warning: %s\033[0m%n", v);
            }
        }

        if (hasHard) {
            List<String> hardMessages = violations.stream()
                .filter(v -> v.severity() == Severity.HARD)
                .map(Violation::toString)
                .toList();
            throw new IllegalStateException(
                "Cross-loader type-safety check failed — incompatible dependency versions: "
                + hardMessages
            );
        }
    }
}
