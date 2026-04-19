package org.intermed.core.resolver;

/**
 * Structured resolver diagnostic emitted alongside a resolved plan.
 */
public record ResolutionDiagnostic(
    Severity severity,
    String code,
    String moduleId,
    String message
) {

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }
}
