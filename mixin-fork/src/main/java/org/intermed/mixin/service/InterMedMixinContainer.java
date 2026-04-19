package org.intermed.mixin.service;

import org.spongepowered.asm.launch.platform.container.IContainerHandle;

import java.util.Collection;
import java.util.Collections;

/**
 * {@link IContainerHandle} representing the InterMed platform container.
 * Mixin uses this to read resources (mixin config JSON files) from the container.
 */
public final class InterMedMixinContainer implements IContainerHandle {

    private final String name;

    public InterMedMixinContainer(String name) {
        this.name = name;
    }

    @Override
    public String getAttribute(String name) {
        if ("Implementation-Title".equals(name) || "Name".equals(name)) return this.name;
        return null;
    }

    @Override
    public Collection<IContainerHandle> getNestedContainers() {
        return Collections.emptyList();
    }

    @Override
    public String toString() {
        return "InterMedMixinContainer[" + name + "]";
    }
}
