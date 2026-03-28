package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.Capability;
import org.intermed.core.security.CapabilityManager;
import java.lang.reflect.AccessibleObject;

/**
 * Модуль безопасности InterMed (Реализация Требования 8 ТЗ).
 * Перехватывает вызовы java.lang.reflect.AccessibleObject.setAccessible.
 */
public class ReflectionSecurityAdvice {

    @Advice.OnMethodEnter
    public static void checkReflectionAccess(@Advice.This AccessibleObject accessibleObject) {
        StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        Class<?> caller = walker.getCallerClass();
        String callerName = caller.getName();

        // Пропускаем ядро платформы, ванильный Minecraft и стандартную библиотеку Java
        if (callerName.startsWith("java.") || callerName.startsWith("sun.") || 
            callerName.startsWith("org.intermed.") || callerName.startsWith("net.minecraft.")) {
            return;
        }

        // Требование 8 ТЗ: Проверка запрошенного действия через политику возможностей (Capabilities)
        // Предотвращает несанкционированный взлом инкапсуляции через рефлексию
        CapabilityManager.checkPermission(Capability.REFLECTION_ACCESS.name(), caller);
    }
}