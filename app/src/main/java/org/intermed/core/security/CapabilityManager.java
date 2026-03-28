package org.intermed.core.security;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджер политик безопасности (Capability-based Security).
 * ТЗ 3.2.5 (Требование 8).
 */
public class CapabilityManager {
    
    // TODO: В будущем маппинг "Загрузчик мода -> Набор прав" должен читаться из конфигураций.
    // Пока заводим общий сет для тестирования.
    private static final Set<String> GRANTED_CAPABILITIES = ConcurrentHashMap.newKeySet();

    static {
        GRANTED_CAPABILITIES.add("NETWORK_CONNECT");
        // FILE_READ и UNSAFE_ACCESS намеренно не добавлены для проверки работы защиты
    }

    public static void checkPermission(String capability, Class<?> caller) {
        if (!GRANTED_CAPABILITIES.contains(capability)) {
            throw new SecurityException("\033[1;31m[InterMed Security] Моду " + caller.getName() + 
                    " ОТКАЗАНО в разрешении: " + capability + "\033[0m");
        }
    }
}