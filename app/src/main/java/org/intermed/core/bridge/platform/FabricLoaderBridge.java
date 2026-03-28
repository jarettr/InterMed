package org.intermed.core.bridge.platform;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class FabricLoaderBridge {
    private static final FabricLoaderBridge INSTANCE = new FabricLoaderBridge();

    public static FabricLoaderBridge getInstance() {
        return INSTANCE;
    }

    public Path getConfigDir() {
        return FMLPaths.CONFIGDIR.get();
    }

    public Path getGameDir() {
        return FMLPaths.GAMEDIR.get();
    }

    public boolean isModLoaded(String id) {
        if (id.equals("fabric-api") || id.equals("fabric")) return true;
        return ModList.get() != null && ModList.get().isLoaded(id);
    }

    // Заглушка для получения точек входа
    public List<Object> getEntrypointContainers(String key, Class<?> type) {
        return new ArrayList<>();
    }
}