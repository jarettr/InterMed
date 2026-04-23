package net.fabricmc.loader.api;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.intermed.core.bridge.platform.FabricLoaderBridge;
import org.intermed.core.config.RuntimeConfig;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface FabricLoader {
    FabricLoader INSTANCE = new FabricLoader() {};
    FabricLoaderBridge BRIDGE = FabricLoaderBridge.getInstance();
    
    static FabricLoader getInstance() { return INSTANCE; }

    default Path getConfigDir() { return BRIDGE.getConfigDir(); }
    default Path getGameDir() { return BRIDGE.getGameDir(); }
    
    default boolean isModLoaded(String id) { return BRIDGE.isModLoaded(id); }
    default boolean isDevelopmentEnvironment() {
        return Boolean.getBoolean("intermed.dev") || Boolean.getBoolean("fabric.development");
    }
    default EnvType getEnvironmentType() { return RuntimeConfig.get().getEnvironmentType(); }
    
    default Optional<ModContainer> getModContainer(String id) {
        return BRIDGE.getModContainer(id);
    }

    default Collection<ModContainer> getAllMods() {
        return BRIDGE.getAllMods();
    }

    default <T> List<T> getEntrypoints(String key, Class<T> type) {
        return BRIDGE.getEntrypoints(key, type);
    }

    default <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
        return BRIDGE.getEntrypointContainers(key, type);
    }
}
