package org.intermed.core.resolver;

import java.util.List;

/**
 * Structured failure thrown when the resolver cannot build a valid plan.
 */
public final class ResolutionException extends IllegalStateException {

    private final String code;
    private final String moduleId;
    private final List<String> requirements;
    private final List<String> availableVersions;

    public ResolutionException(String code,
                               String moduleId,
                               List<String> requirements,
                               List<String> availableVersions,
                               String message) {
        super(message);
        this.code = code;
        this.moduleId = moduleId;
        this.requirements = requirements == null ? List.of() : List.copyOf(requirements);
        this.availableVersions = availableVersions == null ? List.of() : List.copyOf(availableVersions);
    }

    public String code() {
        return code;
    }

    public String moduleId() {
        return moduleId;
    }

    public List<String> requirements() {
        return requirements;
    }

    public List<String> availableVersions() {
        return availableVersions;
    }
}
