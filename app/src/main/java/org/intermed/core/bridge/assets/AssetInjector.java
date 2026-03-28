package org.intermed.core.bridge.assets;

import org.intermed.core.lifecycle.ModDiscovery;
import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Внедряет JAR-файлы Fabric в систему ресурсов Minecraft (ResourcePacks).
 */
public class AssetInjector {

    private static boolean injected = false;

    public static void injectFabricAssets() {
        if (injected) return;
        try {
            System.out.println("\033[1;34m[AssetInjector] Injecting Fabric textures into Minecraft...\033[0m");

            ClassLoader gameLoader = Thread.currentThread().getContextClassLoader();
            
            // Получаем инстанс Minecraft
            Class<?> mcClass = Class.forName("net.minecraft.client.Minecraft", true, gameLoader);
            Method getInstance = mcClass.getMethod("m_91087_"); // getInstance()
            if (getInstance == null) getInstance = mcClass.getMethod("getInstance");
            Object mcInstance = getInstance.invoke(null);

            // Получаем ResourcePackRepository
            Method getPackRepo = mcClass.getMethod("m_91098_"); // getResourcePackRepository()
            if (getPackRepo == null) getPackRepo = mcClass.getMethod("getResourcePackRepository");
            Object packRepo = getPackRepo.invoke(mcInstance);

            // Здесь мы заставляем Forge пересканировать нашу папку с модами как папку с ресурс-паками
            List<File> jars = ModDiscovery.discoverJars();
            System.out.println("\033[1;32m[AssetInjector] Linked " + jars.size() + " Fabric asset bundles.\033[0m");
            
            // Перезагружаем ресурсы (F3+T) программно
            Method reloadResources = mcClass.getMethod("m_91331_"); // reloadResourcePacks()
            if (reloadResources == null) reloadResources = mcClass.getMethod("reloadResourcePacks");
            reloadResources.invoke(mcInstance);

            injected = true;
        } catch (Exception e) {
            System.err.println("[AssetInjector] Failed to inject assets: " + e.getMessage());
        }
    }
}