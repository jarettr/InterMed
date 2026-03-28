package net.fabricmc.fabric.api.resource;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import java.util.Collection;
import java.util.Collections;

/**
 * Полифилл для Fabric Resource API.
 * Наследует Forge-совместимый интерфейс PreparableReloadListener.
 */
public interface IdentifiableResourceReloadListener extends PreparableReloadListener {
    ResourceLocation getFabricId();

    default Collection<ResourceLocation> getFabricDependencies() {
        return Collections.emptyList();
    }
}