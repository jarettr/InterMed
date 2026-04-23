package org.intermed.core.remapping;

import org.objectweb.asm.ClassReader;
import org.intermed.core.cache.AOTCacheManager;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * High-performance mapping dictionary used by the runtime remapper.
 * Stores class, method and field mappings in internal-name form.
 */
public final class MappingDictionary {
    private static final String AMBIGUOUS_METHOD_ALIAS = "\u0000";
    private static final String UNRESOLVED_METHOD_LOOKUP = "\u0001";

    private final Map<String, String> classes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> methods = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> methodNameAliases = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> fields = new ConcurrentHashMap<>();
    private final Map<String, List<String>> ownerHierarchyCache = new ConcurrentHashMap<>();
    private final Map<MethodLookupKey, String> inheritedMethodLookupCache = new ConcurrentHashMap<>();
    private final Map<MethodAliasLookupKey, String> inheritedMethodAliasLookupCache = new ConcurrentHashMap<>();

    public void clear() {
        classes.clear();
        methods.clear();
        methodNameAliases.clear();
        fields.clear();
        clearDerivedCaches();
    }

    public void addClass(String intermediary, String official) {
        if (intermediary == null || official == null) {
            return;
        }
        clearDerivedCaches();
        classes.put(normalizeInternalName(intermediary), normalizeInternalName(official));
    }

    public void addMethod(String owner, String intermediaryName, String desc, String officialName) {
        if (owner == null || intermediaryName == null || desc == null || officialName == null) {
            return;
        }
        clearDerivedCaches();
        String normalizedOwner = normalizeInternalName(owner);
        methods.computeIfAbsent(normalizedOwner, ignored -> new ConcurrentHashMap<>())
            .put(intermediaryName + desc, officialName);
        registerMethodNameAlias(normalizedOwner, intermediaryName, officialName);
    }

    public void addField(String owner, String intermediaryName, String desc, String officialName) {
        if (owner == null || intermediaryName == null || desc == null || officialName == null) {
            return;
        }
        Map<String, String> ownerFields = fields.computeIfAbsent(normalizeInternalName(owner), ignored -> new ConcurrentHashMap<>());
        ownerFields.put(intermediaryName + desc, officialName);
        ownerFields.put(intermediaryName, officialName);
    }

    public String map(String name) {
        if (name == null) {
            return null;
        }
        return classes.get(normalizeInternalName(name));
    }

    public String mapClassName(String internalName) {
        String mapped = map(internalName);
        return mapped != null ? mapped : internalName;
    }

    public String mapMethodName(String owner, String name, String desc) {
        if (owner == null || name == null || desc == null) {
            return name;
        }
        String normalizedOwner = normalizeInternalName(owner);
        for (String candidateOwner : directOwnerCandidates(normalizedOwner)) {
            String mapped = findMethodMappingOnOwner(candidateOwner, name, desc);
            if (mapped != null) {
                return mapped;
            }
        }
        String alias = findDirectMethodNameAlias(normalizedOwner, name);
        if (alias != null) {
            return alias;
        }
        String inheritedMapped = findInheritedMethodMapping(normalizedOwner, name, desc);
        if (inheritedMapped != null) {
            return inheritedMapped;
        }
        String inheritedAlias = findInheritedMethodNameAlias(normalizedOwner, name);
        if (inheritedAlias != null) {
            return inheritedAlias;
        }
        if (name.startsWith("method_")) {
            return "m_" + name.substring(7) + "_";
        }
        return name;
    }

    private void registerMethodNameAlias(String owner, String intermediaryName, String officialName) {
        methodNameAliases.computeIfAbsent(owner, ignored -> new ConcurrentHashMap<>())
            .merge(intermediaryName, officialName, (existing, incoming) -> {
                if (existing.equals(incoming) || existing.equals(AMBIGUOUS_METHOD_ALIAS)) {
                    return existing;
                }
                return AMBIGUOUS_METHOD_ALIAS;
            });
    }

    private String findDirectMethodNameAlias(String owner, String name) {
        for (String candidateOwner : directOwnerCandidates(owner)) {
            String mapped = findMethodNameAliasOnOwner(candidateOwner, name);
            if (mapped != null) {
                return mapped;
            }
        }
        return null;
    }

    private String findMethodNameAliasOnOwner(String owner, String name) {
        Map<String, String> ownerAliases = methodNameAliases.get(normalizeInternalName(owner));
        if (ownerAliases == null) {
            return null;
        }
        String mapped = ownerAliases.get(name);
        return AMBIGUOUS_METHOD_ALIAS.equals(mapped) ? null : mapped;
    }

    private String findMethodMappingOnOwner(String owner, String name, String desc) {
        Map<String, String> ownerMethods = methods.get(normalizeInternalName(owner));
        if (ownerMethods == null) {
            return null;
        }
        return ownerMethods.get(name + desc);
    }

    private String findInheritedMethodMapping(String owner, String name, String desc) {
        MethodLookupKey key = new MethodLookupKey(normalizeInternalName(owner), name, desc);
        String cached = inheritedMethodLookupCache.get(key);
        if (cached != null) {
            return UNRESOLVED_METHOD_LOOKUP.equals(cached) ? null : cached;
        }

        String resolved = null;
        for (String inheritedOwner : inheritedOwnerCandidates(key.owner())) {
            resolved = findMethodMappingOnOwner(inheritedOwner, key.name(), key.desc());
            if (resolved != null) {
                break;
            }
        }

        inheritedMethodLookupCache.put(key, resolved == null ? UNRESOLVED_METHOD_LOOKUP : resolved);
        return resolved;
    }

    private String findInheritedMethodNameAlias(String owner, String name) {
        MethodAliasLookupKey key = new MethodAliasLookupKey(normalizeInternalName(owner), name);
        String cached = inheritedMethodAliasLookupCache.get(key);
        if (cached != null) {
            return UNRESOLVED_METHOD_LOOKUP.equals(cached) ? null : cached;
        }

        String resolved = null;
        for (String inheritedOwner : inheritedOwnerCandidates(key.owner())) {
            resolved = findMethodNameAliasOnOwner(inheritedOwner, key.name());
            if (resolved != null) {
                break;
            }
        }

        inheritedMethodAliasLookupCache.put(key, resolved == null ? UNRESOLVED_METHOD_LOOKUP : resolved);
        return resolved;
    }

    private List<String> directOwnerCandidates(String owner) {
        String normalizedOwner = normalizeInternalName(owner);
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(normalizedOwner);

        String mappedOwner = map(normalizedOwner);
        if (mappedOwner != null) {
            candidates.add(normalizeInternalName(mappedOwner));
        }
        return List.copyOf(candidates);
    }

    private List<String> inheritedOwnerCandidates(String owner) {
        LinkedHashSet<String> inheritedOwners = new LinkedHashSet<>();
        Set<String> directOwners = new LinkedHashSet<>(directOwnerCandidates(owner));
        for (String directOwner : directOwners) {
            for (String hierarchyOwner : ownerHierarchyCache.computeIfAbsent(directOwner, this::computeOwnerHierarchy)) {
                if (!directOwners.contains(hierarchyOwner)) {
                    inheritedOwners.add(hierarchyOwner);
                }
            }
        }
        return inheritedOwners.isEmpty() ? List.of() : List.copyOf(inheritedOwners);
    }

    private List<String> computeOwnerHierarchy(String owner) {
        LinkedHashSet<String> hierarchy = new LinkedHashSet<>();
        collectOwnerHierarchy(normalizeInternalName(owner), hierarchy, new LinkedHashSet<>());
        return hierarchy.isEmpty() ? List.of() : List.copyOf(hierarchy);
    }

    private void collectOwnerHierarchy(String owner, LinkedHashSet<String> hierarchy, Set<String> visited) {
        String normalizedOwner = normalizeInternalName(owner);
        if (!visited.add(normalizedOwner)) {
            return;
        }
        hierarchy.add(normalizedOwner);

        ClassShape shape = readClassShape(normalizedOwner);
        if (shape == null) {
            return;
        }
        if (shape.superName() != null) {
            collectOwnerHierarchy(shape.superName(), hierarchy, visited);
        }
        for (String interfaceName : shape.interfaceNames()) {
            collectOwnerHierarchy(interfaceName, hierarchy, visited);
        }
    }

    private ClassShape readClassShape(String internalName) {
        ClassShape fromBytecode = readClassShapeFromBytecode(internalName);
        return fromBytecode != null ? fromBytecode : readClassShapeReflectively(internalName);
    }

    private ClassShape readClassShapeFromBytecode(String internalName) {
        String resourceName = normalizeInternalName(internalName) + ".class";
        for (ClassLoader loader : candidateLoaders()) {
            try (InputStream input = loader == null
                ? ClassLoader.getSystemResourceAsStream(resourceName)
                : loader.getResourceAsStream(resourceName)) {
                if (input == null) {
                    continue;
                }
                ClassReader reader = new ClassReader(input);
                return new ClassShape(reader.getSuperName(), List.of(reader.getInterfaces()));
            } catch (IOException ignored) {
                return null;
            }
        }
        return null;
    }

    private ClassShape readClassShapeReflectively(String internalName) {
        String binaryName = normalizeInternalName(internalName).replace('/', '.');
        for (ClassLoader loader : candidateLoaders()) {
            try {
                Class<?> type = Class.forName(binaryName, false, loader);
                Class<?> superClass = type.getSuperclass();
                String superName = superClass == null ? null : normalizeInternalName(superClass.getName());
                List<String> interfaceNames = java.util.Arrays.stream(type.getInterfaces())
                    .map(Class::getName)
                    .map(MappingDictionary::normalizeInternalName)
                    .toList();
                return new ClassShape(superName, interfaceNames);
            } catch (ClassNotFoundException | LinkageError ignored) {
                // Try the next loader.
            }
        }
        return null;
    }

    private List<ClassLoader> candidateLoaders() {
        ClassLoader threadLoader = Thread.currentThread().getContextClassLoader();
        ClassLoader localLoader = MappingDictionary.class.getClassLoader();
        LinkedHashSet<ClassLoader> loaders = new LinkedHashSet<>();
        if (threadLoader != null) {
            loaders.add(threadLoader);
        }
        if (localLoader != null) {
            loaders.add(localLoader);
        }
        ClassLoader systemLoader = ClassLoader.getSystemClassLoader();
        if (systemLoader != null) {
            loaders.add(systemLoader);
        }
        return loaders.isEmpty() ? List.of() : List.copyOf(loaders);
    }

    private void clearDerivedCaches() {
        ownerHierarchyCache.clear();
        inheritedMethodLookupCache.clear();
        inheritedMethodAliasLookupCache.clear();
    }

    public String mapFieldName(String owner, String name, String desc) {
        if (owner == null || name == null || desc == null) {
            return name;
        }
        Map<String, String> ownerFields = fields.get(normalizeInternalName(owner));
        if (ownerFields == null) {
            String mappedOwner = map(owner);
            if (mappedOwner != null) {
                ownerFields = fields.get(normalizeInternalName(mappedOwner));
            }
        }
        if (ownerFields != null) {
            String mapped = ownerFields.get(name + desc);
            if (mapped == null) {
                mapped = ownerFields.get(name);
            }
            if (mapped != null) {
                return mapped;
            }
        }
        if (name.startsWith("field_")) {
            return "f_" + name.substring(6) + "_";
        }
        return name;
    }

    public boolean hasMappings() {
        return !classes.isEmpty() || !methods.isEmpty() || !fields.isEmpty();
    }

    public int getClassCount() {
        return classes.size();
    }

    public int getMethodCount() {
        return methods.values().stream()
            .mapToInt(Map::size)
            .sum();
    }

    public int getFieldCount() {
        return fields.values().stream()
            .mapToInt(Map::size)
            .sum();
    }

    public void updateClass(String intermediary, String official) {
        classes.computeIfPresent(normalizeInternalName(intermediary),
            (ignored, existing) -> normalizeInternalName(official));
    }

    public Map<String, String> classesView() {
        return Collections.unmodifiableMap(classes);
    }

    public String fingerprint() {
        String classDescriptor = classes.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "->" + entry.getValue())
            .collect(Collectors.joining("|"));
        String methodDescriptor = methods.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(methodEntry -> methodEntry.getKey() + "->" + methodEntry.getValue())
                .collect(Collectors.joining(",")))
            .collect(Collectors.joining("|"));
        String fieldDescriptor = fields.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(fieldEntry -> fieldEntry.getKey() + "->" + fieldEntry.getValue())
                .collect(Collectors.joining(",")))
            .collect(Collectors.joining("|"));
        return AOTCacheManager.sha256("classes=" + classDescriptor + "|methods=" + methodDescriptor + "|fields=" + fieldDescriptor);
    }

    static String normalizeInternalName(String name) {
        return name.indexOf('.') >= 0 ? name.replace('.', '/') : name;
    }

    private record MethodLookupKey(String owner, String name, String desc) {}

    private record MethodAliasLookupKey(String owner, String name) {}

    private record ClassShape(String superName, List<String> interfaceNames) {}
}
