package org.intermed.core.remapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.objectweb.asm.Type;

/**
 * Small runtime cache for lazy symbolic lookups.
 *
 * <p>This is the Remapping 2.0 "symbolic TLB": once a symbolic class/member
 * name is resolved to the concrete runtime symbol, later reflection lookups
 * reuse the cached result instead of repeating dictionary scans.
 */
final class SymbolicTranslationTlb {

    private static final Map<ClassKey, Class<?>> CLASS_CACHE = new ConcurrentHashMap<>();
    private static final Map<MethodKey, Method> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<FieldKey, Field> FIELD_CACHE = new ConcurrentHashMap<>();

    private SymbolicTranslationTlb() {}

    static Class<?> getClass(ClassLoader loader, String requestedName) {
        return CLASS_CACHE.get(new ClassKey(loader, requestedName));
    }

    static void putClass(ClassLoader loader, String requestedName, Class<?> resolvedClass) {
        if (requestedName == null || requestedName.isBlank() || resolvedClass == null) {
            return;
        }
        CLASS_CACHE.put(new ClassKey(loader, requestedName), resolvedClass);
    }

    static Method getMethod(Class<?> owner, String requestedName, Class<?>[] parameterTypes, boolean declaredOnly) {
        return METHOD_CACHE.get(MethodKey.of(owner, requestedName, parameterTypes, declaredOnly));
    }

    static void putMethod(Class<?> owner, String requestedName, Class<?>[] parameterTypes, boolean declaredOnly, Method method) {
        if (owner == null || requestedName == null || method == null) {
            return;
        }
        METHOD_CACHE.put(MethodKey.of(owner, requestedName, parameterTypes, declaredOnly), method);
    }

    static Field getField(Class<?> owner, String requestedName, boolean declaredOnly) {
        return FIELD_CACHE.get(new FieldKey(owner, requestedName, declaredOnly));
    }

    static void putField(Class<?> owner, String requestedName, boolean declaredOnly, Field field) {
        if (owner == null || requestedName == null || field == null) {
            return;
        }
        FIELD_CACHE.put(new FieldKey(owner, requestedName, declaredOnly), field);
    }

    static void clear() {
        CLASS_CACHE.clear();
        METHOD_CACHE.clear();
        FIELD_CACHE.clear();
    }

    private record ClassKey(ClassLoader loader, String requestedName) {}

    private record FieldKey(Class<?> owner, String requestedName, boolean declaredOnly) {}

    private record MethodKey(Class<?> owner, String requestedName, String descriptor, boolean declaredOnly) {
        private static MethodKey of(Class<?> owner, String requestedName, Class<?>[] parameterTypes, boolean declaredOnly) {
            Type[] args = parameterTypes == null
                ? new Type[0]
                : java.util.Arrays.stream(parameterTypes).map(Type::getType).toArray(Type[]::new);
            String descriptor = Type.getMethodDescriptor(Type.VOID_TYPE, args);
            return new MethodKey(owner, requestedName, descriptor, declaredOnly);
        }
    }
}
