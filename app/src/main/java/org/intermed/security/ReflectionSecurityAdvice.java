package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.CapabilityManager;
import java.lang.reflect.AccessibleObject;

/**
 * Модуль безопасности InterMed (Реализация Требования 8 ТЗ).
 * Перехватывает вызовы java.lang.reflect.AccessibleObject.setAccessible.
 */
public class ReflectionSecurityAdvice {

    @Advice.OnMethodEnter
    public static void checkReflectionAccess(@Advice.This AccessibleObject accessibleObject,
                                             @Advice.Origin("#t.#m") String origin) {
        CapabilityManager.checkReflectionAccess(origin);
    }
}
