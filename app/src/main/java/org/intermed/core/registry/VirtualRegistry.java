package org.intermed.core.registry;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Быстрое in-memory хранилище для Bootstrap методов (RegistryLinker).
 */
public class VirtualRegistry {
    private static final ConcurrentHashMap<String, Object> REGISTRY_MAP = new ConcurrentHashMap<>();

    public static Object getFast(String name) {
        return REGISTRY_MAP.get(name);
    }

    public static void register(String name, Object instance) {
        REGISTRY_MAP.put(name, instance);
    }
}