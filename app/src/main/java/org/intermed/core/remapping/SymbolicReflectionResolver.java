package org.intermed.core.remapping;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import org.intermed.core.config.RuntimeConfig;
import org.objectweb.asm.Type;

/**
 * Lazy symbolic resolver for reflection-heavy code paths.
 *
 * <p>Unlike the legacy bytecode-first remapper, this resolver waits until the
 * runtime actually asks for a class/member and only then resolves the symbolic
 * name against the mapping dictionary. Successful resolutions are cached in the
 * symbolic TLB so repeated lookups become constant-time.
 */
public final class SymbolicReflectionResolver {

    private SymbolicReflectionResolver() {}

    public static Class<?> resolveClass(String requestedName, ClassLoader preferredLoader) throws ClassNotFoundException {
        String normalized = normalizeClassName(requestedName);
        if (normalized == null || normalized.isBlank()) {
            throw new ClassNotFoundException(String.valueOf(requestedName));
        }

        if (!RuntimeConfig.get().isSymbolicRemappingEnabled()) {
            return Class.forName(InterMedRemapper.translateRuntimeString(normalized), false, preferredLoader);
        }

        Class<?> cached = SymbolicTranslationTlb.getClass(preferredLoader, normalized);
        if (cached != null) {
            return cached;
        }

        ClassNotFoundException lastFailure = null;
        for (String candidate : classNameCandidates(normalized)) {
            try {
                Class<?> resolved = Class.forName(candidate, false, preferredLoader);
                cacheResolvedClass(preferredLoader, normalized, candidate, resolved);
                return resolved;
            } catch (ClassNotFoundException failure) {
                lastFailure = failure;
            }
        }

        ClassLoader tccl = Thread.currentThread().getContextClassLoader();
        if (!Objects.equals(tccl, preferredLoader)) {
            for (String candidate : classNameCandidates(normalized)) {
                try {
                    Class<?> resolved = Class.forName(candidate, false, tccl);
                    cacheResolvedClass(preferredLoader, normalized, candidate, resolved);
                    return resolved;
                } catch (ClassNotFoundException failure) {
                    lastFailure = failure;
                }
            }
        }

        throw lastFailure != null ? lastFailure : new ClassNotFoundException(normalized);
    }

    public static Method resolveMethod(Class<?> owner,
                                       String requestedName,
                                       Class<?>[] parameterTypes,
                                       boolean declaredOnly) throws NoSuchMethodException {
        Objects.requireNonNull(owner, "owner");
        String normalizedName = requestedName == null ? "" : requestedName.trim();
        Class<?>[] safeParameters = parameterTypes == null ? new Class<?>[0] : parameterTypes;

        if (!RuntimeConfig.get().isSymbolicRemappingEnabled()) {
            return directMethodLookup(owner, InterMedRemapper.translateRuntimeString(normalizedName), safeParameters, declaredOnly);
        }

        Method cached = SymbolicTranslationTlb.getMethod(owner, normalizedName, safeParameters, declaredOnly);
        if (cached != null) {
            return cached;
        }

        NoSuchMethodException lastFailure = null;
        for (String candidate : methodNameCandidates(owner, normalizedName, safeParameters)) {
            try {
                Method resolved = directMethodLookup(owner, candidate, safeParameters, declaredOnly);
                makeAccessible(resolved);
                SymbolicTranslationTlb.putMethod(owner, normalizedName, safeParameters, declaredOnly, resolved);
                SymbolicTranslationTlb.putMethod(owner, resolved.getName(), safeParameters, declaredOnly, resolved);
                return resolved;
            } catch (NoSuchMethodException failure) {
                lastFailure = failure;
            }
        }

        Method rescued = rescueMethod(owner, normalizedName, safeParameters, declaredOnly);
        if (rescued != null) {
            makeAccessible(rescued);
            SymbolicTranslationTlb.putMethod(owner, normalizedName, safeParameters, declaredOnly, rescued);
            SymbolicTranslationTlb.putMethod(owner, rescued.getName(), safeParameters, declaredOnly, rescued);
            return rescued;
        }

        throw lastFailure != null ? lastFailure : new NoSuchMethodException(owner.getName() + "#" + normalizedName);
    }

    public static Field resolveField(Class<?> owner,
                                     String requestedName,
                                     boolean declaredOnly) throws NoSuchFieldException {
        Objects.requireNonNull(owner, "owner");
        String normalizedName = requestedName == null ? "" : requestedName.trim();

        if (!RuntimeConfig.get().isSymbolicRemappingEnabled()) {
            return directFieldLookup(owner, InterMedRemapper.translateRuntimeString(normalizedName), declaredOnly);
        }

        Field cached = SymbolicTranslationTlb.getField(owner, normalizedName, declaredOnly);
        if (cached != null) {
            return cached;
        }

        NoSuchFieldException lastFailure = null;
        for (String candidate : fieldNameCandidates(owner, normalizedName)) {
            try {
                Field resolved = directFieldLookup(owner, candidate, declaredOnly);
                makeAccessible(resolved);
                SymbolicTranslationTlb.putField(owner, normalizedName, declaredOnly, resolved);
                SymbolicTranslationTlb.putField(owner, resolved.getName(), declaredOnly, resolved);
                return resolved;
            } catch (NoSuchFieldException failure) {
                lastFailure = failure;
            }
        }

        throw lastFailure != null ? lastFailure : new NoSuchFieldException(owner.getName() + "#" + normalizedName);
    }

    private static Method directMethodLookup(Class<?> owner,
                                             String name,
                                             Class<?>[] parameterTypes,
                                             boolean declaredOnly) throws NoSuchMethodException {
        return declaredOnly
            ? owner.getDeclaredMethod(name, parameterTypes)
            : owner.getMethod(name, parameterTypes);
    }

    private static Field directFieldLookup(Class<?> owner,
                                           String name,
                                           boolean declaredOnly) throws NoSuchFieldException {
        return declaredOnly ? owner.getDeclaredField(name) : owner.getField(name);
    }

    private static List<String> classNameCandidates(String requestedName) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(normalizeClassName(requestedName));
        candidates.add(normalizeClassName(InterMedRemapper.translateRuntimeString(requestedName)));
        return candidates.stream()
            .filter(candidate -> candidate != null && !candidate.isBlank())
            .toList();
    }

    private static List<String> methodNameCandidates(Class<?> owner,
                                                     String requestedName,
                                                     Class<?>[] parameterTypes) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(requestedName);

        String ownerInternal = owner.getName().replace('.', '/');
        String parameterDescriptor = syntheticMethodDescriptor(parameterTypes);
        candidates.add(LifecycleAwareDictionary.mapMethodName(ownerInternal, requestedName, parameterDescriptor));

        String translated = InterMedRemapper.translateRuntimeString(requestedName);
        candidates.add(translated);
        if (requestedName.startsWith("method_")) {
            candidates.add("m_" + requestedName.substring("method_".length()) + "_");
        }
        return candidates.stream()
            .filter(candidate -> candidate != null && !candidate.isBlank())
            .toList();
    }

    private static List<String> fieldNameCandidates(Class<?> owner, String requestedName) {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(requestedName);

        String ownerInternal = owner.getName().replace('.', '/');
        candidates.add(LifecycleAwareDictionary.mapFieldName(ownerInternal, requestedName, ""));

        String translated = InterMedRemapper.translateRuntimeString(requestedName);
        candidates.add(translated);
        if (requestedName.startsWith("field_")) {
            candidates.add("f_" + requestedName.substring("field_".length()) + "_");
        }
        return candidates.stream()
            .filter(candidate -> candidate != null && !candidate.isBlank())
            .toList();
    }

    private static Method rescueMethod(Class<?> owner,
                                       String requestedName,
                                       Class<?>[] parameterTypes,
                                       boolean declaredOnly) {
        List<Method> candidates = new ArrayList<>();
        for (Method method : declaredOnly ? owner.getDeclaredMethods() : owner.getMethods()) {
            if (!Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                continue;
            }
            if (memberNameLooksCompatible(method.getName(), requestedName)) {
                candidates.add(method);
            }
        }

        if (candidates.size() == 1) {
            return candidates.get(0);
        }

        if (candidates.isEmpty()) {
            for (Method method : declaredOnly ? owner.getDeclaredMethods() : owner.getMethods()) {
                if (Arrays.equals(method.getParameterTypes(), parameterTypes)) {
                    candidates.add(method);
                }
            }
            if (candidates.size() == 1) {
                return candidates.get(0);
            }
        }

        return null;
    }

    private static boolean memberNameLooksCompatible(String runtimeName, String requestedName) {
        if (runtimeName == null || requestedName == null) {
            return false;
        }
        if (runtimeName.equals(requestedName)) {
            return true;
        }
        if (runtimeName.equals(InterMedRemapper.translateRuntimeString(requestedName))) {
            return true;
        }
        if (requestedName.startsWith("method_")) {
            return runtimeName.equals("m_" + requestedName.substring("method_".length()) + "_");
        }
        if (requestedName.startsWith("field_")) {
            return runtimeName.equals("f_" + requestedName.substring("field_".length()) + "_");
        }
        return false;
    }

    private static String syntheticMethodDescriptor(Class<?>[] parameterTypes) {
        Type[] args = Arrays.stream(parameterTypes == null ? new Class<?>[0] : parameterTypes)
            .map(Type::getType)
            .toArray(Type[]::new);
        return Type.getMethodDescriptor(Type.VOID_TYPE, args);
    }

    private static void cacheResolvedClass(ClassLoader preferredLoader,
                                           String requestedName,
                                           String resolvedCandidate,
                                           Class<?> resolvedClass) {
        SymbolicTranslationTlb.putClass(preferredLoader, requestedName, resolvedClass);
        if (resolvedCandidate != null && !resolvedCandidate.equals(requestedName)) {
            SymbolicTranslationTlb.putClass(preferredLoader, resolvedCandidate, resolvedClass);
        }
        SymbolicTranslationTlb.putClass(resolvedClass.getClassLoader(), requestedName, resolvedClass);
        SymbolicTranslationTlb.putClass(resolvedClass.getClassLoader(), resolvedClass.getName(), resolvedClass);
    }

    private static String normalizeClassName(String requestedName) {
        if (requestedName == null) {
            return null;
        }
        String trimmed = requestedName.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        return trimmed.replace('/', '.');
    }

    private static void makeAccessible(AccessibleObject accessibleObject) {
        if (accessibleObject == null) {
            return;
        }
        try {
            accessibleObject.trySetAccessible();
        } catch (Exception ignored) {
        }
    }

    private static final class LifecycleAwareDictionary {
        private static String mapMethodName(String owner, String name, String descriptor) {
            return org.intermed.core.lifecycle.LifecycleManager.DICTIONARY.mapMethodName(owner, name, descriptor);
        }

        private static String mapFieldName(String owner, String name, String descriptor) {
            return org.intermed.core.lifecycle.LifecycleManager.DICTIONARY.mapFieldName(owner, name, descriptor);
        }
    }
}
