package org.intermed.core.sandbox;

import java.util.Locale;

/**
 * Supported runtime isolation targets for Phase 4.
 */
public enum SandboxMode {
    NATIVE,
    ESPRESSO,
    WASM;

    public static SandboxMode fromString(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return NATIVE;
        }
        return switch (rawValue.trim().toLowerCase(Locale.ROOT)) {
            case "espresso", "java", "jvm_espresso" -> ESPRESSO;
            case "wasm", "chicory", "wasm_chicory" -> WASM;
            default -> NATIVE;
        };
    }

    public String externalName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
