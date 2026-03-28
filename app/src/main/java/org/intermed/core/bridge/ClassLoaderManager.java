package org.intermed.core.bridge;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ClassLoaderManager {
    private static final Map<String, InterMedTransformingClassLoader> loaders = new ConcurrentHashMap<>();

    public static void setup() {
        System.out.println("\033[1;34m[InterMed] ClassLoaderManager: Граф загрузчиков готов.\033[0m");
    }

    public static void register(String modId, InterMedTransformingClassLoader loader) {
        loaders.put(modId, loader);
    }
}