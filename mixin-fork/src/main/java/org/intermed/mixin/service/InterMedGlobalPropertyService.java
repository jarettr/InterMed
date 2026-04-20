package org.intermed.mixin.service;

import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Minimal concurrent global property store for Sponge Mixin runtime state.
 */
public final class InterMedGlobalPropertyService implements IGlobalPropertyService {

    private static final Map<String, PropertyKey> KEYS = new ConcurrentHashMap<>();
    private static final Map<PropertyKey, Object> VALUES = new ConcurrentHashMap<>();

    @Override
    public IPropertyKey resolveKey(String name) {
        return KEYS.computeIfAbsent(name == null ? "" : name, PropertyKey::new);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key) {
        return key == null ? null : (T) VALUES.get(key);
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        if (!(key instanceof PropertyKey propertyKey)) {
            return;
        }
        if (value == null) {
            VALUES.remove(propertyKey);
            return;
        }
        VALUES.put(propertyKey, value);
    }

    @Override
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        T value = getProperty(key);
        return value != null ? value : defaultValue;
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        Object value = getProperty(key);
        return value != null ? String.valueOf(value) : defaultValue;
    }

    public static void resetForTests() {
        KEYS.clear();
        VALUES.clear();
    }

    private record PropertyKey(String name) implements IPropertyKey {
        @Override
        public String toString() {
            return name;
        }
    }
}
