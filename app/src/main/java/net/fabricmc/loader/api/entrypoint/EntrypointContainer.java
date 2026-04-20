package net.fabricmc.loader.api.entrypoint;

import net.fabricmc.loader.api.ModContainer;

/**
 * Minimal Fabric Loader entrypoint container contract backed by the InterMed bridge runtime.
 */
public interface EntrypointContainer<T> {
    T getEntrypoint();

    ModContainer getProvider();

    default String getDefinition() {
        return getEntrypoint().getClass().getName();
    }
}
