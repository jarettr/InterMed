package org.intermed.core.bridge.assets;

import org.intermed.core.bridge.forge.ForgePackRepositoryBridge;
import org.intermed.core.metadata.NormalizedModMetadata;
import org.intermed.core.metadata.RuntimeModIndex;
import org.intermed.core.vfs.VirtualFileSystemRouter;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.CompletionStage;
import java.lang.reflect.Field;
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
            Method getInstance = findZeroArgMethod(mcClass, "N", "m_91087_", "getInstance");
            Object mcInstance = getInstance.invoke(null);

            Object packRepo = resolvePackRepository(mcClass, mcInstance);

            List<File> jars = collectResourceJars();
            System.out.println("\033[1;32m[AssetInjector] Linked " + jars.size() + " Fabric asset bundles.\033[0m");

            if (!jars.isEmpty() && packRepo != null) {
                ForgePackRepositoryBridge.injectIntoPackRepository(packRepo);
            }
            
            // Перезагружаем ресурсы (F3+T) программно
            invokeReloadResources(mcClass, mcInstance);

            injected = true;
        } catch (Exception e) {
            System.err.println("[AssetInjector] Failed to inject assets: " + describeThrowable(e));
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

    private static Method findZeroArgMethod(Class<?> owner, String... candidateNames) throws NoSuchMethodException {
        for (String name : candidateNames) {
            if (name == null || name.isBlank()) {
                continue;
            }
            try {
                return owner.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                // Try next candidate.
            }
        }
        throw new NoSuchMethodException(owner.getName() + " zero-arg method " + String.join("/", candidateNames));
    }

    private static Method findMethodByReturnType(Class<?> owner,
                                                 String returnTypeName,
                                                 String... candidateNames) throws NoSuchMethodException {
        Class<?> cursor = owner;
        while (cursor != null) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getParameterCount() != 0) {
                    continue;
                }
                String methodName = method.getName();
                boolean candidate = false;
                for (String name : candidateNames) {
                    if (methodName.equals(name)) {
                        candidate = true;
                        break;
                    }
                }
                if (!candidate) {
                    continue;
                }
                if (method.getReturnType().getName().equals(returnTypeName)) {
                    method.setAccessible(true);
                    return method;
                }
            }
            cursor = cursor.getSuperclass();
        }
        throw new NoSuchMethodException(owner.getName() + " -> " + returnTypeName + " via " + String.join("/", candidateNames));
    }

    private static Method findMethodByReturnType(Class<?> owner,
                                                 Class<?> returnType,
                                                 String... candidateNames) throws NoSuchMethodException {
        Class<?> cursor = owner;
        while (cursor != null) {
            for (Method method : cursor.getDeclaredMethods()) {
                if (method.getParameterCount() != 0) {
                    continue;
                }
                String methodName = method.getName();
                boolean candidate = false;
                for (String name : candidateNames) {
                    if (methodName.equals(name)) {
                        candidate = true;
                        break;
                    }
                }
                if (!candidate) {
                    continue;
                }
                if (returnType.isAssignableFrom(method.getReturnType())) {
                    method.setAccessible(true);
                    return method;
                }
            }
            cursor = cursor.getSuperclass();
        }
        throw new NoSuchMethodException(owner.getName() + " -> " + returnType.getName() + " via " + String.join("/", candidateNames));
    }

    private static Object resolvePackRepository(Class<?> mcClass, Object mcInstance) throws ReflectiveOperationException {
        try {
            Method getPackRepo = findMethodByReturnType(
                mcClass,
                "net.minecraft.server.packs.repository.PackRepository",
                "Z",
                "getResourcePackRepository"
            );
            return getPackRepo.invoke(mcInstance);
        } catch (NoSuchMethodException ignored) {
            // Fall through to a structural lookup so the runtime shape, not a single name, decides.
        }

        Field repositoryField = findFieldByType(
            mcClass,
            "net.minecraft.server.packs.repository.PackRepository",
            "al",
            "resourcePackRepository",
            "packRepository"
        );
        return repositoryField.get(mcInstance);
    }

    private static Field findFieldByType(Class<?> owner,
                                         String fieldTypeName,
                                         String... candidateNames) throws NoSuchFieldException {
        Class<?> cursor = owner;
        while (cursor != null) {
            for (String name : candidateNames) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                try {
                    Field field = cursor.getDeclaredField(name);
                    if (field.getType().getName().equals(fieldTypeName)) {
                        field.setAccessible(true);
                        return field;
                    }
                } catch (NoSuchFieldException ignored) {
                    // Try next candidate or structural fallback.
                }
            }

            for (Field field : cursor.getDeclaredFields()) {
                if (field.getType().getName().equals(fieldTypeName)) {
                    field.setAccessible(true);
                    return field;
                }
            }
            cursor = cursor.getSuperclass();
        }
        throw new NoSuchFieldException(owner.getName() + " -> " + fieldTypeName + " via " + String.join("/", candidateNames));
    }

    private static void invokeReloadResources(Class<?> mcClass, Object mcInstance) throws ReflectiveOperationException {
        try {
            Method reloadResources = findMethodByReturnType(
                mcClass,
                CompletionStage.class,
                "j",
                "m_91331_",
                "reloadResourcePacks"
            );
            reloadResources.invoke(mcInstance);
            return;
        } catch (NoSuchMethodException ignored) {
            // Fall through to structural lookup.
        }

        for (Method method : mcClass.getDeclaredMethods()) {
            if (method.getParameterCount() == 0 && CompletionStage.class.isAssignableFrom(method.getReturnType())) {
                method.setAccessible(true);
                method.invoke(mcInstance);
                return;
            }
        }

        for (Method method : mcClass.getDeclaredMethods()) {
            if (method.getParameterCount() == 1
                && method.getParameterTypes()[0] == boolean.class
                && CompletionStage.class.isAssignableFrom(method.getReturnType())
                && (method.getName().equals("e")
                    || method.getName().equals("reloadResourcePacks")
                    || method.getName().equals("m_91331_"))) {
                method.setAccessible(true);
                method.invoke(mcInstance, false);
                return;
            }
        }

        throw new NoSuchMethodException(mcClass.getName() + " reloadResourcePacks CompletionStage method");
    }

    private static String describeThrowable(Throwable throwable) {
        Throwable root = throwable;
        while (root instanceof InvocationTargetException invocation && invocation.getCause() != null) {
            root = invocation.getCause();
        }
        String message = root.getMessage();
        return root.getClass().getSimpleName() + ": " + (message == null ? "<no message>" : message);
    }
}
