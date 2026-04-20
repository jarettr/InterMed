package net.fabricmc.loader.api;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.intermed.core.bridge.platform.FabricLoaderBridge;
import org.intermed.core.config.RuntimeConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public class FabricLoader {
    private static final FabricLoader INSTANCE = new FabricLoader();
    private final FabricLoaderBridge bridge = FabricLoaderBridge.getInstance();
    
    public static FabricLoader getInstance() { return INSTANCE; }

    public Path getConfigDir() { return bridge.getConfigDir(); }
    public Path getGameDir() { return bridge.getGameDir(); }
    
    public boolean isModLoaded(String id) { return bridge.isModLoaded(id); }
    public boolean isDevelopmentEnvironment() {
        return Boolean.getBoolean("intermed.dev") || Boolean.getBoolean("fabric.development");
    }
    public EnvType getEnvironmentType() { return RuntimeConfig.get().getEnvironmentType(); }
    
    public Optional<ModContainer> getModContainer(String id) {
        return bridge.getModContainer(id);
    }

    public <T> List<T> getEntrypoints(String key, Class<T> type) {
        return bridge.getEntrypoints(key, type);
    }

    public <T> List<EntrypointContainer<T>> getEntrypointContainers(String key, Class<T> type) {
        return bridge.getEntrypointContainers(key, type);
    }
}
