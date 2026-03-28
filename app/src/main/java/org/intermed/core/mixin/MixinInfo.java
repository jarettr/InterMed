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

    public MixinInfo(String name, String mixinClass, List<String> targetClasses, int priority) {
        this.name = name;
        this.mixinClass = mixinClass;
        this.targetClasses = targetClasses;
        this.priority = priority;
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
}
