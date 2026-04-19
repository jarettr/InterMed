package org.intermed.core.transformer;

import org.intermed.core.bridge.NeoForgeEventBridge;
import org.intermed.core.bridge.NeoForgeNetworkBridge;
import org.intermed.core.util.MappingManager;
import org.intermed.core.lifecycle.LifecycleManager;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Критический компонент (Microkernel Layer).
 * Отвечает ТОЛЬКО за отслеживание жизненного цикла игры и запуск фаз инициализации.
 * Никаких жестких манипуляций с байт-кодом здесь больше нет (ТЗ 3.1.7).
 */
public class InterMedTransformer implements ClassFileTransformer {
    private static boolean bridgeStarted = false;
    private static boolean deferredBootstrapReleased = false;
    private static final AtomicInteger classCounter = new AtomicInteger(0);

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        
        if (className == null) return null;

        int count = classCounter.incrementAndGet();
        if (count % 500 == 0) {
            System.out.println("[Kernel] Tracking load state... Class count: " + count);
        }

        String realName = MappingManager.translate(className);
        String nameToCheck = (realName != null) ? realName : className;

        if (nameToCheck.startsWith("net/neoforged/") || nameToCheck.startsWith("net.neoforged.")) {
            NeoForgeEventBridge.scheduleRegistrationProbe();
            NeoForgeNetworkBridge.scheduleRegistrationProbe();
        }

        if (!deferredBootstrapReleased
            && Boolean.getBoolean("intermed.deferDeepBootstrap")
            && (nameToCheck.contains("gui/screens/TitleScreen")
                || nameToCheck.contains("server/MinecraftServer"))) {
            deferredBootstrapReleased = true;
            System.out.println("[Kernel] Releasing deferred Phase 0 bootstrap at: " + nameToCheck);
            LifecycleManager.startPhase0_Preloader();
        }

        // --- ТРИГГЕР ГЛАВНОГО МЕНЮ (Снятие барьера Фазы 0) ---
        if (!bridgeStarted && (nameToCheck.contains("gui/screens/TitleScreen") || nameToCheck.contains("server/MinecraftServer"))) {
            System.out.println("\033[1;35m[Lifecycle] CRITICAL GAME STATE REACHED: " + nameToCheck + "\033[0m");
            bridgeStarted = true;
            
            // Включаем скрытый хук событий Forge
            try {
                org.intermed.core.bridge.events.ForgeEventProxy.hookIntoForge();
                org.intermed.core.bridge.InterMedEventBridge.initialize();
            } catch (Exception e) {
                System.err.println("[Kernel] Failed to inject Forge hook: " + e.getMessage());
            }

            // ЗАПУСКАЕМ ФАЗУ 1: Фоновая асинхронная подготовка модов (ТЗ 3.1.3)
            LifecycleManager.startPhase1_BackgroundAssembly();
        }

        // Возвращаем null. JVM сама загрузит классы без изменений.
        // За ремаппинг и миксины теперь отвечает LazyInterMedClassLoader.
        return null; 
    }
}
