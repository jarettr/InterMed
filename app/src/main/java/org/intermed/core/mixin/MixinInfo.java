package org.intermed.core.mixin;

import java.util.List;

/**
 * A data class to hold information about a single Mixin configuration.
 */
public class MixinInfo {
    private final String name;
    private final String mixinClass;
    private final List<String> targetClasses;
    private final int priority;
    private final int registrationOrder;
    /** The version of the mod that owns this mixin, e.g. {@code "1.2.3"}. */
    private final String modVersion;

    public MixinInfo(String name, String mixinClass, List<String> targetClasses, int priority) {
        this(name, mixinClass, targetClasses, priority, Integer.MAX_VALUE, "unknown");
    }

    public MixinInfo(String name, String mixinClass, List<String> targetClasses,
                     int priority, String modVersion) {
        this(name, mixinClass, targetClasses, priority, Integer.MAX_VALUE, modVersion);
    }

    public MixinInfo(String name, String mixinClass, List<String> targetClasses,
                     int priority, int registrationOrder, String modVersion) {
        this.name = name;
        this.mixinClass = mixinClass;
        this.targetClasses = targetClasses;
        this.priority = priority;
        this.registrationOrder = registrationOrder;
        this.modVersion = modVersion == null || modVersion.isBlank() ? "unknown" : modVersion;
    }

    public String getName() {
        return name;
    }

    public String getMixinClass() {
        return mixinClass;
    }

    public List<String> getTargetClasses() {
        return targetClasses;
    }

    public int getPriority() {
        return priority;
    }

    public int getRegistrationOrder() {
        return registrationOrder;
    }

    /** Version string of the owning mod. Never null; defaults to {@code "unknown"}. */
    public String getModVersion() {
        return modVersion;
    }
}
