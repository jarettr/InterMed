package org.intermed.core.remapping;

import java.util.HashMap;
import java.util.Map;

/**
 * Высокопроизводительное хранилище маппингов (Phase 1: Prepare).
 * Оптимизировано для мгновенного поиска O(1).
 */
public class MappingDictionary {
    // Хранилище классов: Intermediary -> Official
    public final Map<String, String> classes = new HashMap<>();
    
    // Хранилище методов: Owner -> (InterName + Descriptor) -> OfficialName
    public final Map<String, Map<String, String>> methods = new HashMap<>();
    
    // Хранилище полей: Owner -> (InterName + Descriptor) -> OfficialName
    public final Map<String, Map<String, String>> fields = new HashMap<>();

    public void addClass(String inter, String off) {
        classes.put(inter, off);
    }

    public void addMethod(String owner, String interName, String desc, String offName) {
        methods.computeIfAbsent(owner, k -> new HashMap<>()).put(interName + desc, offName);
    }

    public void addField(String owner, String interName, String desc, String offName) {
        fields.computeIfAbsent(owner, k -> new HashMap<>()).put(interName + desc, offName);
    }

    public String map(String name) {
        return classes.getOrDefault(name, name);
    }

    public int getClassCount() {
        return classes.size();
    }
    // Метод для обновления маппинга (используется при слиянии словарей)
    public void updateClass(String inter, String off) {
        if (classes.containsKey(inter)) {
            classes.put(inter, off);
        }
    }
}