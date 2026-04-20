package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;

/**
 * Модуль безопасности InterMed (Реализация Требования 8 ТЗ).
 * Перехватывает вызовы sun.misc.Unsafe для предотвращения обхода песочницы.
 */
public class UnsafeSecurityAdvice {

    @Advice.OnMethodEnter
    public static void checkUnsafeAccess(@Advice.Origin("#m") String memberName) {
        CapabilityManager.checkUnsafeOperation(memberName);
    }
}
