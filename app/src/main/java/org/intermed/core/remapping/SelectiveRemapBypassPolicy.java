package org.intermed.core.remapping;

import java.util.List;

/**
 * Routes known reflective / shaded libraries away from legacy owner-member
 * remapping and keeps them on the symbolic reflection path only.
 *
 * <p>These libraries are high-risk for bytecode-first remapping because they
 * synthesize names dynamically, use reflection heavily, or ship shaded helper
 * code whose symbols should remain opaque to the Minecraft dictionary.
 */
final class SelectiveRemapBypassPolicy {
    private static final List<String> SYMBOLIC_ONLY_PREFIXES = List.of(
        "org.reflections.",
        "javassist.",
        "me.shedaniel.cloth.clothconfig.shadowed."
    );

    private SelectiveRemapBypassPolicy() {}

    static boolean useSymbolicOnly(String className) {
        if (className == null || className.isBlank()) {
            return false;
        }
        String normalized = className.replace('/', '.');
        for (String prefix : SYMBOLIC_ONLY_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
