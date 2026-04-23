package org.intermed.core.mixin;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Reflection-backed fallback for Fabric accessor mixins when the native Mixin
 * runtime cannot post-process the accessor interface before a mod calls it.
 */
public final class MixinAccessorFallback {
    private MixinAccessorFallback() {}

    public static Object getStaticField(Class<?> owner, String name, Class<?> expectedType) {
        return getField(owner, null, name, expectedType, true);
    }

    public static Object getInstanceField(Class<?> owner, Object instance, String name, Class<?> expectedType) {
        if (instance == null) {
            throw new IllegalArgumentException("Accessor target instance is null for " + owner.getName() + "#" + name);
        }
        return getField(owner, instance, name, expectedType, false);
    }

    public static void setStaticField(Class<?> owner, String name, Object value, Class<?> expectedType) {
        setField(owner, null, name, value, expectedType, true);
    }

    public static void setInstanceField(Class<?> owner, Object instance, String name, Object value, Class<?> expectedType) {
        if (instance == null) {
            throw new IllegalArgumentException("Accessor target instance is null for " + owner.getName() + "#" + name);
        }
        setField(owner, instance, name, value, expectedType, false);
    }

    private static Object getField(Class<?> owner, Object instance, String name, Class<?> expectedType, boolean staticOnly) {
        try {
            Field field = resolveField(owner, name, expectedType, staticOnly);
            field.setAccessible(true);
            return field.get(instance);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to read accessor field " + owner.getName() + "#" + name, e);
        }
    }

    private static void setField(Class<?> owner, Object instance, String name, Object value,
                                 Class<?> expectedType, boolean staticOnly) {
        try {
            Field field = resolveField(owner, name, expectedType, staticOnly);
            field.setAccessible(true);
            field.set(instance, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to write accessor field " + owner.getName() + "#" + name, e);
        }
    }

    private static Field resolveField(Class<?> owner, String name, Class<?> expectedType, boolean staticOnly)
            throws NoSuchFieldException {
        for (String candidate : candidateNames(name)) {
            try {
                Field field = owner.getDeclaredField(candidate);
                if (matches(field, expectedType, staticOnly)) {
                    return field;
                }
            } catch (NoSuchFieldException ignored) {
            }
        }

        Field match = null;
        for (Field field : owner.getDeclaredFields()) {
            if (!matches(field, expectedType, staticOnly)) {
                continue;
            }
            if (match != null) {
                throw new NoSuchFieldException("Ambiguous accessor field " + owner.getName() + "#" + name
                    + " for type " + expectedType.getName());
            }
            match = field;
        }
        if (match != null) {
            return match;
        }
        throw new NoSuchFieldException(owner.getName() + "#" + name);
    }

    private static boolean matches(Field field, Class<?> expectedType, boolean staticOnly) {
        if (staticOnly && !Modifier.isStatic(field.getModifiers())) {
            return false;
        }
        return expectedType == Void.TYPE
            || wrap(expectedType).isAssignableFrom(wrap(field.getType()))
            || wrap(field.getType()).isAssignableFrom(wrap(expectedType));
    }

    private static String[] candidateNames(String name) {
        if (name == null || name.isBlank()) {
            return new String[0];
        }
        String lower = Character.toLowerCase(name.charAt(0)) + name.substring(1);
        return new String[] {name, lower};
    }

    private static Class<?> wrap(Class<?> type) {
        if (type == null || !type.isPrimitive()) {
            return type == null ? Object.class : type;
        }
        if (type == Integer.TYPE) return Integer.class;
        if (type == Long.TYPE) return Long.class;
        if (type == Boolean.TYPE) return Boolean.class;
        if (type == Byte.TYPE) return Byte.class;
        if (type == Short.TYPE) return Short.class;
        if (type == Float.TYPE) return Float.class;
        if (type == Double.TYPE) return Double.class;
        if (type == Character.TYPE) return Character.class;
        if (type == Void.TYPE) return Void.class;
        return type;
    }
}
