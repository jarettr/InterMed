package net.fabricmc.loader.api;

import net.fabricmc.api.EnvType;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public class FabricLoader {
    private static final FabricLoader INSTANCE = new FabricLoader();
    
    public static FabricLoader getInstance() { return INSTANCE; }

    public Path getConfigDir() { return Paths.get("config"); }
    public Path getGameDir() { return Paths.get("."); } // Моды часто просят корень игры
    
    public boolean isModLoaded(String id) { return true; } // Обманываем всех
    public boolean isDevelopmentEnvironment() { return false; }
    public EnvType getEnvironmentType() { return EnvType.CLIENT; }
    
    // Заглушка для запроса метаданных мода
    public Optional<ModContainer> getModContainer(String id) {
        return Optional.empty();
    }
}