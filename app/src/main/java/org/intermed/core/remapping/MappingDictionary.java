package org.intermed.core.remapping;

import org.intermed.core.cache.AOTCacheManager;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * High-performance mapping dictionary used by the runtime remapper.
 * Stores class, method and field mappings in internal-name form.
 */
public final class MappingDictionary {
    private final Map<String, String> classes = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> methods = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> fields = new ConcurrentHashMap<>();

    public void clear() {
        classes.clear();
        methods.clear();
        fields.clear();
    }

    public void addClass(String intermediary, String official) {
        if (intermediary == null || official == null) {
            return;
        }
        classes.put(normalizeInternalName(intermediary), normalizeInternalName(official));
    }

    public void addMethod(String owner, String intermediaryName, String desc, String officialName) {
        if (owner == null || intermediaryName == null || desc == null || officialName == null) {
            return;
        }
        methods.computeIfAbsent(normalizeInternalName(owner), ignored -> new ConcurrentHashMap<>())
            .put(intermediaryName + desc, officialName);
    }

    public void addField(String owner, String intermediaryName, String desc, String officialName) {
        if (owner == null || intermediaryName == null || desc == null || officialName == null) {
            return;
        }
        fields.computeIfAbsent(normalizeInternalName(owner), ignored -> new ConcurrentHashMap<>())
            .put(intermediaryName + desc, officialName);
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
        Map<String, String> ownerMethods = methods.get(normalizeInternalName(owner));
        if (ownerMethods == null) {
            String mappedOwner = map(owner);
            if (mappedOwner != null) {
                ownerMethods = methods.get(normalizeInternalName(mappedOwner));
            }
        }
        if (ownerMethods != null) {
            String mapped = ownerMethods.get(name + desc);
            if (mapped != null) {
                return mapped;
            }
        }
        if (name.startsWith("method_")) {
            return "m_" + name.substring(7) + "_";
        }
        return name;
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
}
