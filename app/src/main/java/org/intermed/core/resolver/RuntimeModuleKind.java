package org.intermed.core.resolver;

/**
 * Canonical runtime node kinds used by the dependency resolver and DAG builder.
 *
 * <p>This formalises the distinction between user-provided mods and built-in
 * InterMed runtime modules so bridge/runtime nodes are no longer modelled as
 * anonymous synthetic stubs.
 */
public enum RuntimeModuleKind {
    MOD(false),
    BRIDGE(true),
    PLATFORM_RUNTIME(true),
    CORE_RUNTIME(true);

    private final boolean builtInRuntime;

    RuntimeModuleKind(boolean builtInRuntime) {
        this.builtInRuntime = builtInRuntime;
    }

    public boolean isBuiltInRuntime() {
        return builtInRuntime;
    }

    public static RuntimeModuleKind forModuleId(String moduleId) {
        if (moduleId == null || moduleId.isBlank()) {
            return MOD;
        }
        return switch (moduleId) {
            case "intermed-core" -> CORE_RUNTIME;
            case "intermed-minecraft-runtime",
                 "intermed-java-runtime" -> PLATFORM_RUNTIME;
            case "intermed-fabric-bridge",
                 "intermed-forge-bridge",
                 "intermed-neoforge-bridge" -> BRIDGE;
            default -> MOD;
        };
    }
}
