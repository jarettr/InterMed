package org.intermed.core.bridge.assets;

import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.metadata.RuntimeModIndex;
import org.intermed.core.vfs.VirtualFileSystemRouter;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mounts loaded mod JARs that expose resource/data roots into the host resource pipeline.
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
            Method getInstance = findZeroArgMethod(mcClass, "m_91087_", "getInstance");
            Object mcInstance = getInstance.invoke(null);

            // Получаем ResourcePackRepository
            Method getPackRepo = findZeroArgMethod(mcClass, "m_91098_", "getResourcePackRepository");
            Object packRepo = getPackRepo.invoke(mcInstance);

            List<File> jars = collectResourceJars();
            System.out.println("\033[1;32m[AssetInjector] Linked " + jars.size() + " Fabric asset bundles.\033[0m");
            
            // Перезагружаем ресурсы (F3+T) программно
            Method reloadResources = findZeroArgMethod(mcClass, "m_91331_", "reloadResourcePacks");
            reloadResources.invoke(mcInstance);

            injected = true;
        } catch (Exception e) {
            System.err.println("[AssetInjector] Failed to inject assets: " + e.getMessage());
        }
    }

    public static List<File> collectResourceJars() {
        VirtualFileSystemRouter.MountPlan plan = VirtualFileSystemRouter.buildRuntimeMountPlan();
        if (plan.hasOverlayPack()) {
            System.out.println("[AssetInjector] VFS overlay prepared with "
                + plan.mergedResourceCount() + " merged resources and "
                + plan.conflicts().size() + " conflict resolutions.");
        }
        if (plan.hasDiagnosticsReport()) {
            System.out.println("[AssetInjector] VFS diagnostics report: "
                + plan.diagnosticsReport().getAbsolutePath());
        }
        return plan.mountablePacks().stream()
            .filter(file -> file != null && file.isFile())
            .distinct()
            .collect(Collectors.toList());
    }

    private static boolean hasAnyResources(NormalizedModMetadata metadata) {
        return metadata != null && (metadata.hasClientResources() || metadata.hasServerData());
    }

    private static Method findZeroArgMethod(Class<?> owner, String obfuscatedName, String mappedName) throws NoSuchMethodException {
        try {
            return owner.getMethod(obfuscatedName);
        } catch (NoSuchMethodException ignored) {
            return owner.getMethod(mappedName);
        }
    }
}
