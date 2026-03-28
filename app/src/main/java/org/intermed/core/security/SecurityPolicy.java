package org.intermed.core.security;

import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранит права, выданные каждому конкретному моду.
 */
public class SecurityPolicy {
    
    // Карта: ModID -> Набор разрешений
    private static final Map<String, EnumSet<Capability>> MOD_CAPABILITIES = new ConcurrentHashMap<>();

    static {
        // Заглушка для тестов (В реальности это будет читаться из JSON конфига пользователя)
        // Выдаем AppleSkin только право на чтение (чтобы он мог прочитать свой конфиг)
        MOD_CAPABILITIES.put("appleskin", EnumSet.of(Capability.FILE_READ));
        
        // Системные моды платформы имеют полные права
        MOD_CAPABILITIES.put("intermed_core", EnumSet.allOf(Capability.class));
    }

    public static boolean hasPermission(String modId, Capability capability) {
        if (modId == null) return true; // Системный класс (Java/Forge)
        EnumSet<Capability> caps = MOD_CAPABILITIES.get(modId);
        return caps != null && caps.contains(capability);
    }
}