package org.intermed.core.classloading;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashSet;
import java.util.Set;

/**
 * РЕШЕНИЕ 1: Единый загрузчик для API с фильтрующим делегированием.
 */
public class FabricClusterLoader extends LazyInterMedClassLoader {
    
    // Список префиксов, которые ВСЕГДА загружаются в этом кластере
    private static final String[] FABRIC_PACKAGES = {
        "net.fabricmc.fabric",
        "net.fabricmc.api",
        "io.wispforest.owo", // owo-lib тоже часть ядра
        "dev.onyxstudios.cca" // Cardinal Components
    };

    public FabricClusterLoader(ClassLoader parent) {
        super("fabric-cluster", null, parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Проверяем, относится ли класс к Fabric API
        for (String pkg : FABRIC_PACKAGES) {
            if (name.startsWith(pkg)) {
                // ПРАВИЛО: Эти классы грузим ТОЛЬКО здесь, чтобы они видели друг друга
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) c = findClass(name);
                    if (resolve) resolveClass(c);
                    return c;
                }
            }
        }
        // Остальное (Minecraft, Forge, Java) делегируем родителю
        return super.loadClass(name, resolve);
    }
}