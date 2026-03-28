package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.Capability;
import org.intermed.core.security.CapabilityManager;

/**
 * Модуль безопасности InterMed (Реализация Требования 8 ТЗ).
 * Перехватывает вызовы sun.misc.Unsafe для предотвращения обхода песочницы.
 */
public class UnsafeSecurityAdvice {

    @Advice.OnMethodEnter
    public static void checkUnsafeAccess() {
        StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        Class<?> caller = walker.getCallerClass();
        String callerName = caller.getName();

        // Пропускаем ядро платформы, ванильный Minecraft и стандартную библиотеку Java
        if (callerName.startsWith("java.") || callerName.startsWith("sun.") || 
            callerName.startsWith("org.intermed.") || callerName.startsWith("net.minecraft.")) {
            return;
        }

        // Требование 8 ТЗ: Проверка запрошенного действия через политику возможностей
        // Предотвращает несанкционированное создание объектов или модификацию памяти через Unsafe
        CapabilityManager.checkPermission(Capability.UNSAFE_ACCESS.name(), caller);
    }
}