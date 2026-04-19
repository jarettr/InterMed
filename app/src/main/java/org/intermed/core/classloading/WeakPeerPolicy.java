package org.intermed.core.classloading;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Policy describing a dynamically-installed weak edge between two mod loaders.
 *
 * <p>Weak edges exist only for soft dependencies and expose a deliberately
 * narrow surface: public interface types exported from explicit API packages.
 */
public final class WeakPeerPolicy {

    private final String providerId;
    private final Set<String> apiPrefixes;
    private final boolean interfacesOnly;
    private final ConcurrentHashMap<String, Boolean> classDecisionCache = new ConcurrentHashMap<>();

    public WeakPeerPolicy(String providerId, List<String> apiPrefixes, boolean interfacesOnly) {
        this.providerId = providerId == null ? "unknown" : providerId;
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (apiPrefixes != null) {
            for (String prefix : apiPrefixes) {
                if (prefix == null) {
                    continue;
                }
                String normalizedPrefix = prefix.trim().replace('/', '.');
                if (!normalizedPrefix.isEmpty()) {
                    normalized.add(normalizedPrefix.endsWith(".") ? normalizedPrefix : normalizedPrefix + ".");
                }
            }
        }
        this.apiPrefixes = Set.copyOf(normalized);
        this.interfacesOnly = interfacesOnly;
    }

    public String providerId() {
        return providerId;
    }

    public List<String> apiPrefixes() {
        return apiPrefixes.stream().sorted().toList();
    }

    public boolean interfacesOnly() {
        return interfacesOnly;
    }

    public boolean matchesPrefix(String binaryClassName) {
        if (binaryClassName == null || binaryClassName.isBlank()) {
            return false;
        }
        for (String prefix : apiPrefixes) {
            if (binaryClassName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean allows(String binaryClassName, LazyInterMedClassLoader providerLoader) {
        if (!matchesPrefix(binaryClassName) || providerLoader == null) {
            return false;
        }
        return classDecisionCache.computeIfAbsent(binaryClassName, ignored -> inspectProviderBytes(binaryClassName, providerLoader));
    }

    public WeakPeerPolicy merge(WeakPeerPolicy other) {
        if (other == null) {
            return this;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>(apiPrefixes);
        merged.addAll(other.apiPrefixes);
        return new WeakPeerPolicy(providerId, merged.stream().toList(), interfacesOnly && other.interfacesOnly);
    }

    private boolean inspectProviderBytes(String binaryClassName, LazyInterMedClassLoader providerLoader) {
        byte[] bytes = providerLoader.readLocalClassBytes(binaryClassName);
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        try {
            ClassReader reader = new ClassReader(bytes);
            int access = reader.getAccess();
            boolean publicType = (access & Opcodes.ACC_PUBLIC) != 0;
            boolean interfaceType = (access & Opcodes.ACC_INTERFACE) != 0;
            return publicType && (!interfacesOnly || interfaceType);
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof WeakPeerPolicy policy)) {
            return false;
        }
        return interfacesOnly == policy.interfacesOnly
            && Objects.equals(providerId, policy.providerId)
            && Objects.equals(apiPrefixes, policy.apiPrefixes);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerId, apiPrefixes, interfacesOnly);
    }
}
