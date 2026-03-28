package org.intermed.api;

import org.intermed.core.util.MappingManager;

/**
 * Публичный API платформы InterMed.
 * Предоставляет модам инструменты для взаимодействия с ядром (ТЗ 3.2.4).
 */
public class InterMedAPI {

    /**
     * Программный запрос на ремаппинг имени класса (Требование 7).
     * Позволяет модам, использующим динамическую рефлексию, получать актуальные имена в рантайме.
     * 
     * @param originalName Оригинальное имя класса (например, в стандарте Yarn или MCP)
     * @return Актуальное имя класса для текущей среды выполнения
     */
    public static String remapClassname(String originalName) {
        String internalName = originalName.replace('.', '/');
        String translated = MappingManager.translate(internalName);
        return translated != null ? translated.replace('/', '.') : originalName;
    }
}