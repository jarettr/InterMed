package org.intermed.core.remapping;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays; // <-- ВОТ ЭТОТ ИМПОРТ МЫ ЗАБЫЛИ
import java.util.HashMap;
import java.util.Map;

/**
 * Автономный спасатель. Если статический ремаппинг не сработал (нет в словаре),
 * мы пытаемся угадать нужное поле или метод прямо в рантайме.
 */
public class ReflectionSaviour {
    
    // Кэш, чтобы не искать одно и то же дважды
    private static final Map<String, Method> METHOD_CACHE = new HashMap<>();
    private static final Map<String, Field> FIELD_CACHE = new HashMap<>();

    /**
     * Попытка найти метод, если мы знаем только его параметры.
     * Часто в Fabric-модах методы называются method_XXXX, а в Forge m_XXXX_.
     * Если типы параметров совпадают, мы можем с высокой долей вероятности угадать метод.
     */
    public static Method rescueMethod(Class<?> targetClass, String fabricMethodName, Class<?>... paramTypes) {
        String cacheKey = targetClass.getName() + "#" + fabricMethodName + Arrays.toString(paramTypes);
        if (METHOD_CACHE.containsKey(cacheKey)) return METHOD_CACHE.get(cacheKey);

        // Сначала ищем по точному имени (вдруг он не обфусцирован)
        try {
            Method m = targetClass.getDeclaredMethod(fabricMethodName, paramTypes);
            m.setAccessible(true);
            METHOD_CACHE.put(cacheKey, m);
            return m;
        } catch (NoSuchMethodException ignore) {}

        // Если не нашли, ищем любой метод с совпадающими параметрами (Эвристика)
        for (Method m : targetClass.getDeclaredMethods()) {
            if (Arrays.equals(m.getParameterTypes(), paramTypes)) {
                // Нашли метод с такими же аргументами!
                // Для надежности можно проверить, начинается ли он с "m_" (SRG формат)
                if (m.getName().startsWith("m_") || m.getName().length() <= 3) {
                    System.out.println("\033[1;36m[Saviour] Rescued Method: " + targetClass.getSimpleName() + "." + fabricMethodName + " -> " + m.getName() + "\033[0m");
                    m.setAccessible(true);
                    METHOD_CACHE.put(cacheKey, m);
                    return m;
                }
            }
        }
        
        System.err.println("[Saviour] Failed to rescue method: " + fabricMethodName + " in " + targetClass.getName());
        return null;
    }

    /**
     * Попытка найти поле, если мы знаем его тип.
     * Fabric: field_XXXX, Forge: f_XXXX_
     */
    public static Field rescueField(Class<?> targetClass, String fabricFieldName, Class<?> fieldType) {
        String cacheKey = targetClass.getName() + "#" + fabricFieldName;
        if (FIELD_CACHE.containsKey(cacheKey)) return FIELD_CACHE.get(cacheKey);

        try {
            Field f = targetClass.getDeclaredField(fabricFieldName);
            f.setAccessible(true);
            FIELD_CACHE.put(cacheKey, f);
            return f;
        } catch (NoSuchFieldException ignore) {}

        // Ищем поле по типу
        for (Field f : targetClass.getDeclaredFields()) {
            if (f.getType().equals(fieldType)) {
                System.out.println("\033[1;36m[Saviour] Rescued Field: " + targetClass.getSimpleName() + "." + fabricFieldName + " -> " + f.getName() + "\033[0m");
                f.setAccessible(true);
                FIELD_CACHE.put(cacheKey, f);
                return f;
            }
        }
        
        System.err.println("[Saviour] Failed to rescue field: " + fabricFieldName + " in " + targetClass.getName());
        return null;
    }
}