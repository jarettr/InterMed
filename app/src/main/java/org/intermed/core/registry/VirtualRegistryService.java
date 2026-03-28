package org.intermed.core.registry;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Сервис виртуализации глобальных реестров (ТЗ 3.2.2).
 * Шардирует пространства имен во избежание конфликтов ID между модами разных экосистем.
 */
public class VirtualRegistryService {
    
    private static final ConcurrentHashMap<String, Integer> VIRTUAL_IDS = new ConcurrentHashMap<>();
    // Начинаем с безопасного диапазона, чтобы не конфликтовать с ванильными ID Minecraft (< 10000)
    private static final AtomicInteger ID_GENERATOR = new AtomicInteger(100000); 

    /**
     * Разрешает или генерирует виртуальный числовой ID для строкового идентификатора.
     * @param namespacePath Полный строковый ID объекта (например, "modid:item_name")
     * @param originalId Оригинальный числовой ID, если он известен, иначе -1
     * @return Безопасный глобальный виртуальный ID
     */
    public static int resolveVirtualId(String namespacePath, int originalId) {
        return VIRTUAL_IDS.computeIfAbsent(namespacePath, key -> {
            // Генерируем уникальный инкрементный ID для предотвращения коллизий
            return ID_GENERATOR.getAndIncrement();
        });
    }
}