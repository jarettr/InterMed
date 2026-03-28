package org.intermed.security;

import net.bytebuddy.asm.Advice;
import java.io.File;
import org.intermed.core.security.Capability;
import org.intermed.core.security.CapabilityManager;

/**
 * Модуль безопасности InterMed v8.0 (Реализация Требования 8 ТЗ)
 */
public class FileSecurityAdvice {

    /**
     * Этот код будет "вклеен" (inlined) в конструктор FileInputStream с помощью ByteBuddy.
     */
    @Advice.OnMethodEnter
    public static void checkFileAccess(@Advice.Argument(0) File file) {
        if (file == null) return;

        // Требование 8 ТЗ: Использование StackWalker для определения инициатора
        StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        
        // Получаем класс, который физически запросил открытие файла
        Class<?> caller = walker.getCallerClass();
        String callerName = caller.getName();

        String path = file.getAbsolutePath().toLowerCase();
        
        // Пропускаем ядро платформы, ванильный Minecraft и стандартную библиотеку Java
        if (callerName.startsWith("java.") || callerName.startsWith("sun.") || callerName.startsWith("org.intermed.") || callerName.startsWith("net.minecraft.")) {
            return;
        }

        // Требование 8 ТЗ: Проверка запрошенного действия через политику возможностей (Capabilities)
        CapabilityManager.checkPermission(Capability.FILE_READ.name(), caller);
    }
}