package org.intermed.core.remapping;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.intermed.core.config.RuntimeConfig;

/**
 * Runtime facade injected into transformed reflection call sites.
 *
 * <p>When symbolic remapping is enabled, these wrappers resolve classes and
 * members lazily via {@link SymbolicReflectionResolver}. When disabled, they
 * preserve the legacy behavior by translating raw strings and then delegating
 * to the original JDK reflection API.
 */
public final class SymbolicReflectionFacade {

    private SymbolicReflectionFacade() {}

    public static Class<?> forName(String name) throws ClassNotFoundException {
        if (!RuntimeConfig.get().isSymbolicRemappingEnabled()) {
            return Class.forName(InterMedRemapper.translateRuntimeString(name));
        }
        return SymbolicReflectionResolver.resolveClass(name, Thread.currentThread().getContextClassLoader());
    }

    public static Class<?> forName(String name, boolean initialize, ClassLoader loader) throws ClassNotFoundException {
        if (!RuntimeConfig.get().isSymbolicRemappingEnabled()) {
            return Class.forName(InterMedRemapper.translateRuntimeString(name), initialize, loader);
        }
        Class<?> resolved = SymbolicReflectionResolver.resolveClass(name, loader);
        return initialize ? Class.forName(resolved.getName(), true, resolved.getClassLoader()) : resolved;
    }

    public static Class<?> loadClass(ClassLoader loader, String name) throws ClassNotFoundException {
        if (!RuntimeConfig.get().isSymbolicRemappingEnabled()) {
            return loader.loadClass(InterMedRemapper.translateRuntimeString(name));
        }
        return SymbolicReflectionResolver.resolveClass(name, loader);
    }

    public static Class<?> findClass(ClassLoader loader, String name) throws ClassNotFoundException {
        String translated = RuntimeConfig.get().isSymbolicRemappingEnabled()
            ? name
            : InterMedRemapper.translateRuntimeString(name);
        try {
            return invokeProtectedFindClass(loader, translated);
        } catch (ClassNotFoundException notFound) {
            if (!RuntimeConfig.get().isSymbolicRemappingEnabled()) {
                throw notFound;
            }
            return SymbolicReflectionResolver.resolveClass(name, loader);
        }
    }

    public static Method getMethod(Class<?> owner, String name, Class<?>[] parameterTypes) throws NoSuchMethodException {
        return SymbolicReflectionResolver.resolveMethod(owner, name, parameterTypes, false);
    }

    public static Method getDeclaredMethod(Class<?> owner, String name, Class<?>[] parameterTypes) throws NoSuchMethodException {
        return SymbolicReflectionResolver.resolveMethod(owner, name, parameterTypes, true);
    }

    public static Field getField(Class<?> owner, String name) throws NoSuchFieldException {
        return SymbolicReflectionResolver.resolveField(owner, name, false);
    }

    public static Field getDeclaredField(Class<?> owner, String name) throws NoSuchFieldException {
        return SymbolicReflectionResolver.resolveField(owner, name, true);
    }

    public static MethodHandle findVirtual(MethodHandles.Lookup lookup,
                                           Class<?> owner,
                                           String name,
                                           MethodType type) throws NoSuchMethodException, IllegalAccessException {
        if (!RuntimeConfig.get().isSymbolicRemappingEnabled()) {
            return lookup.findVirtual(owner, InterMedRemapper.translateRuntimeString(name), type);
        }
        Method resolved = resolveLookupMethod(owner, name, type.parameterArray());
        try {
            return lookup.findVirtual(owner, resolved.getName(), type);
        } catch (NoSuchMethodException originalFailure) {
            return lookup.unreflect(resolved);
        }
    }

    public static MethodHandle findStatic(MethodHandles.Lookup lookup,
                                          Class<?> owner,
                                          String name,
                                          MethodType type) throws NoSuchMethodException, IllegalAccessException {
        if (!RuntimeConfig.get().isSymbolicRemappingEnabled()) {
            return lookup.findStatic(owner, InterMedRemapper.translateRuntimeString(name), type);
        }
        Method resolved = resolveLookupMethod(owner, name, type.parameterArray());
        try {
            return lookup.findStatic(owner, resolved.getName(), type);
        } catch (NoSuchMethodException originalFailure) {
            return lookup.unreflect(resolved);
        }
    }

    public static MethodHandle findSpecial(MethodHandles.Lookup lookup,
                                           Class<?> owner,
                                           String name,
                                           MethodType type,
                                           Class<?> specialCaller) throws NoSuchMethodException, IllegalAccessException {
        if (!RuntimeConfig.get().isSymbolicRemappingEnabled()) {
            return lookup.findSpecial(owner, InterMedRemapper.translateRuntimeString(name), type, specialCaller);
        }
        Method resolved = resolveLookupMethod(owner, name, type.parameterArray());
        try {
            return lookup.findSpecial(owner, resolved.getName(), type, specialCaller);
        } catch (NoSuchMethodException originalFailure) {
            return lookup.unreflectSpecial(resolved, specialCaller);
        }
    }

    public static MethodHandle findGetter(MethodHandles.Lookup lookup,
                                          Class<?> owner,
                                          String name,
                                          Class<?> type) throws NoSuchFieldException, IllegalAccessException {
        return resolveGetter(lookup, owner, name, type, false);
    }

    public static MethodHandle findSetter(MethodHandles.Lookup lookup,
                                          Class<?> owner,
                                          String name,
                                          Class<?> type) throws NoSuchFieldException, IllegalAccessException {
        return resolveSetter(lookup, owner, name, type, false);
    }

    public static MethodHandle findStaticGetter(MethodHandles.Lookup lookup,
                                                Class<?> owner,
                                                String name,
                                                Class<?> type) throws NoSuchFieldException, IllegalAccessException {
        return resolveGetter(lookup, owner, name, type, true);
    }

    public static MethodHandle findStaticSetter(MethodHandles.Lookup lookup,
                                                Class<?> owner,
                                                String name,
                                                Class<?> type) throws NoSuchFieldException, IllegalAccessException {
        return resolveSetter(lookup, owner, name, type, true);
    }

    private static MethodHandle resolveGetter(MethodHandles.Lookup lookup,
                                              Class<?> owner,
                                              String name,
                                              Class<?> type,
                                              boolean isStatic) throws NoSuchFieldException, IllegalAccessException {
        if (!RuntimeConfig.get().isSymbolicRemappingEnabled()) {
            String translated = InterMedRemapper.translateRuntimeString(name);
            return isStatic ? lookup.findStaticGetter(owner, translated, type) : lookup.findGetter(owner, translated, type);
        }
        Field resolved = SymbolicReflectionResolver.resolveField(owner, name, false);
        try {
            return isStatic
                ? lookup.findStaticGetter(owner, resolved.getName(), type)
                : lookup.findGetter(owner, resolved.getName(), type);
        } catch (NoSuchFieldException originalFailure) {
            return lookup.unreflectGetter(resolved);
        }
    }

    private static MethodHandle resolveSetter(MethodHandles.Lookup lookup,
                                              Class<?> owner,
                                              String name,
                                              Class<?> type,
                                              boolean isStatic) throws NoSuchFieldException, IllegalAccessException {
        if (!RuntimeConfig.get().isSymbolicRemappingEnabled()) {
            String translated = InterMedRemapper.translateRuntimeString(name);
            return isStatic ? lookup.findStaticSetter(owner, translated, type) : lookup.findSetter(owner, translated, type);
        }
        Field resolved = SymbolicReflectionResolver.resolveField(owner, name, false);
        try {
            return isStatic
                ? lookup.findStaticSetter(owner, resolved.getName(), type)
                : lookup.findSetter(owner, resolved.getName(), type);
        } catch (NoSuchFieldException originalFailure) {
            return lookup.unreflectSetter(resolved);
        }
    }

    private static Method resolveLookupMethod(Class<?> owner,
                                              String name,
                                              Class<?>[] parameterTypes) throws NoSuchMethodException {
        try {
            return SymbolicReflectionResolver.resolveMethod(owner, name, parameterTypes, false);
        } catch (NoSuchMethodException ignored) {
            return SymbolicReflectionResolver.resolveMethod(owner, name, parameterTypes, true);
        }
    }

    private static Class<?> invokeProtectedFindClass(ClassLoader loader, String name) throws ClassNotFoundException {
        if (loader == null) {
            return Class.forName(name);
        }
        Class<?> cursor = loader.getClass();
        while (cursor != null) {
            try {
                Method method = cursor.getDeclaredMethod("findClass", String.class);
                method.trySetAccessible();
                return (Class<?>) method.invoke(loader, name);
            } catch (NoSuchMethodException ignored) {
                cursor = cursor.getSuperclass();
            } catch (InvocationTargetException invocationFailure) {
                Throwable cause = invocationFailure.getCause();
                if (cause instanceof ClassNotFoundException classNotFound) {
                    throw classNotFound;
                }
                throw new ClassNotFoundException(name, cause);
            } catch (IllegalAccessException | RuntimeException inaccessible) {
                break;
            }
        }
        return loader.loadClass(name);
    }
}
