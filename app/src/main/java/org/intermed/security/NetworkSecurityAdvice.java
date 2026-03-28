package org.intermed.security;

import net.bytebuddy.asm.Advice;
import org.intermed.core.security.Capability;
import org.intermed.core.security.CapabilityManager;

/**
 * Модуль безопасности InterMed (Реализация Требования 8 ТЗ).
 * Перехватывает сетевые соединения для предотвращения отправки данных на сторонние серверы.
 */
public class NetworkSecurityAdvice {

    /**
     * Метод вклеивается ByteBuddy в java.net.Socket.connect() или sun.net.www.protocol.http.HttpURLConnection.connect()
     */
    @Advice.OnMethodEnter
    public static void checkNetworkAccess() {
        StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
        Class<?> caller = walker.getCallerClass();
        String callerName = caller.getName();

        // Пропускаем ядро платформы, ванильный Minecraft и стандартную библиотеку Java
        if (callerName.startsWith("java.") || callerName.startsWith("sun.") || 
            callerName.startsWith("org.intermed.") || callerName.startsWith("net.minecraft.")) {
            return;
        }

        // Требование 8 ТЗ: Проверка запрошенного действия через политику возможностей (Capabilities)
        // Если моду не выдано разрешение "NETWORK_CONNECT", выбросится SecurityException
        CapabilityManager.checkPermission(Capability.NETWORK_CONNECT.name(), caller);
    }
}